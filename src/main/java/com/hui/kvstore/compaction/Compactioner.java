package com.hui.kvstore.compaction;

import com.alibaba.fastjson.JSONObject;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.hui.kvstore.model.Position;
import com.hui.kvstore.model.command.Command;
import com.hui.kvstore.model.command.RmCommand;
import com.hui.kvstore.model.sstable.SsTable;
import com.hui.kvstore.utils.BlockUtils;
import com.hui.kvstore.utils.ConvertUtil;
import com.hui.kvstore.utils.FileUtils;
import com.hui.kvstore.utils.LoggerUtil;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * compaction执行器
 *
 * 触发机制：
 * level0根据sstable文件数
 * 其他level根据文件总大小
 */
public class Compactioner {
    private final Logger LOGGER = LoggerFactory.getLogger(Compactioner.class);

    /**
     * Level 0的sstable文件个数超过此阈值，触发compaction
     */
    private final static Integer L0_DUMP_MAX_FILE_NUM = 3;

    /**
     * 其他level的sstable文件总大小超过此阈值，触发compaction（单位：byte）
     */
    private final static Integer L_OTHER_DUMP_MAX_FILE_SIZE = 100;

    /**
     * 用来执行compaction的线程池
     */
    private ExecutorService compactionExecutor;

    /**
     * compaction执行结果
     */
    private Future<?> backgroundCompactionFuture;

    /**
     * 锁
     */
    private final ReentrantLock mutex = new ReentrantLock();

    public Compactioner() {
        ThreadFactory compactionThreadFactory = new ThreadFactoryBuilder()
                .setNameFormat("hui-kvstore-compaction-%s")
                .setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler()
                {
                    @Override
                    public void uncaughtException(Thread t, Throwable e)
                    {
                        e.printStackTrace();
                    }
                })
                .build();
        compactionExecutor = Executors.newSingleThreadExecutor(compactionThreadFactory);
    }

    /**
     * compaction操作
     *
     * @param levelMetaInfos 每一层的sstable信息
     */
    public void compaction(Map<Integer, List<SsTable>> levelMetaInfos,
                           AtomicLong nextFileNumber) {
        mutex.lock();

        //同步执行
        try {
            doBackgroundCompaction(levelMetaInfos, nextFileNumber);
        } catch (Exception e) {
            LoggerUtil.error(LOGGER, e, "doBackgroundCompaction exception,", e);
        }

        /**
        //提交线程池异步执行
        this.backgroundCompactionFuture = compactionExecutor.submit(new Callable<Void>() {
            @Override
            public Void call() {
                try {
                    doBackgroundCompaction(levelMetaInfos, nextFileNumber);
                } catch (Exception e) {
                    System.out.println("doBackgroundCompaction,exception," + e);
                    e.printStackTrace();
                }
                return null;
            }
        });
         **/

        mutex.unlock();
    }

    /**
     * 执行compaction操作
     *
     * @param levelMetaInfos
     * @param nextFileNumber
     */
    private void doBackgroundCompaction(Map<Integer, List<SsTable>> levelMetaInfos,
                                        AtomicLong nextFileNumber) throws IOException {
        //TODO 并发处理

        //先判断l0是否应该触发compaction
        if (levelMetaInfos.get(0).size() <= L0_DUMP_MAX_FILE_NUM) {
            return;
        }

        LoggerUtil.debug(LOGGER, "doBackgroundCompaction begin...levelMetaInfos=" + levelMetaInfos);

        //1. 执行l0的compaction
        //找出和当前新产生的sstable存在重合key的sstable，和下一level的存在key重合的sstable进行合并，写入到下一level

        //当前新产生的l0的sstable一定是文件编号最大的
        List<SsTable> l0Sstables  = levelMetaInfos.get(0);
        Optional<SsTable> maxFileNumOptional = l0Sstables.stream().max(Comparator.comparingLong(SsTable::getFileNumber));
        SsTable lastL0SsTable = maxFileNumOptional.get();

        LoggerUtil.debug(LOGGER, "doBackgroundCompaction,lastL0SsTable=" + lastL0SsTable);

        List<SsTable> overlappedL0SSTableFileMetaInfos = findOverlapSstables(
            lastL0SsTable.getTableMetaInfo().getSmallestKey(), lastL0SsTable.getTableMetaInfo().getLargestKey(),
            l0Sstables);

        LoggerUtil.debug(LOGGER, "doBackgroundCompaction,overlappedL0SSTableFileMetaInfos=" + overlappedL0SSTableFileMetaInfos);

        //计算一批sstable文件覆盖的key范围
        String smallestKey = null;
        String largestKey = null;
        for (SsTable ssTableFileMetaInfo : overlappedL0SSTableFileMetaInfos) {
            if (smallestKey == null || smallestKey.compareTo(ssTableFileMetaInfo.getTableMetaInfo().getSmallestKey()) > 0) {
                smallestKey = ssTableFileMetaInfo.getTableMetaInfo().getSmallestKey();
            }
            if (largestKey == null || largestKey.compareTo(ssTableFileMetaInfo.getTableMetaInfo().getLargestKey()) < 0) {
                largestKey = ssTableFileMetaInfo.getTableMetaInfo().getLargestKey();
            }
        }

        LoggerUtil.debug(LOGGER, "doBackgroundCompaction,smallestKey=" + smallestKey + ",largestKey=" + largestKey);

        //再获取l1存在重合key的所有sstable
        List<SsTable> overlappedL1SSTableFileMetaInfos = findOverlapSstables(
                smallestKey, largestKey, levelMetaInfos.get(1));

        LoggerUtil.debug(LOGGER, "doBackgroundCompaction,overlappedL1SSTableFileMetaInfos=" + overlappedL1SSTableFileMetaInfos);

        //合并为1个sstable文件，放置到l1

        //l0所有文件排序，文件编号越小的表示越早的文件，相同的key，以最新的数据为准
        List<SsTable> overlappedSstables = new ArrayList<>();
        if (!CollectionUtils.isEmpty(overlappedL0SSTableFileMetaInfos)) {
            overlappedSstables.addAll(overlappedL0SSTableFileMetaInfos);
        }
        if (!CollectionUtils.isEmpty(overlappedL1SSTableFileMetaInfos)) {
            overlappedSstables.addAll(overlappedL1SSTableFileMetaInfos);
        }
        overlappedSstables.sort((sstabl1, sstable2) -> {
            if (sstabl1.getFileNumber() < sstable2.getFileNumber()) {
                return -1;
            } else if (sstabl1.getFileNumber() == sstable2.getFileNumber()) {
                return 0;
            } else {
                return 1;
            }
        });

        //合并后的结果
        ConcurrentSkipListMap<String, Command> mergedData = new ConcurrentSkipListMap<>();
        mergeSstables(overlappedSstables, mergedData);

        LoggerUtil.debug(LOGGER, "doBackgroundCompaction...开始生成的sstable文件,overlappedL0SSTableFileMetaInfos"
                + overlappedL0SSTableFileMetaInfos + ",overlappedL1SSTableFileMetaInfos="
                + overlappedL1SSTableFileMetaInfos + ",mergedData=" + mergedData);

        //生成新的sstable文件
        SsTable newSsTable = SsTable.createFromIndex(nextFileNumber.getAndIncrement(), 4,
                mergedData, true, 1);
        List<SsTable> l1Sstables = levelMetaInfos.get(1);
        if (l1Sstables == null) {
            l1Sstables =  new ArrayList<>();
            levelMetaInfos.put(1, l1Sstables);
        }
        l1Sstables.add(newSsTable);
        //删除不需要的sstable
        Iterator l0SstableIterator = l0Sstables.iterator();
        while (l0SstableIterator.hasNext()) {
            SsTable tempSsTable = (SsTable) l0SstableIterator.next();
            if (containTheSstable(overlappedL0SSTableFileMetaInfos, tempSsTable.getFileNumber())) {
                l0SstableIterator.remove();
                tempSsTable.close();
                File tmpSstableFile = new File(FileUtils.buildSstableFilePath(tempSsTable.getFileNumber(), 0));
                tmpSstableFile.delete();
            }
        }
        Iterator l1SstableIterator = l1Sstables.iterator();
        while (l1SstableIterator.hasNext()) {
            SsTable tempSsTable = (SsTable) l1SstableIterator.next();
            if (containTheSstable(overlappedL1SSTableFileMetaInfos, tempSsTable.getFileNumber())) {
                l1SstableIterator.remove();
                tempSsTable.close();
                File tmpSstableFile = new File(FileUtils.buildSstableFilePath(tempSsTable.getFileNumber(), 0));
                tmpSstableFile.delete();
            }
        }

        LoggerUtil.debug(LOGGER, "doBackgroundCompaction...完成l0的compaction,levelMetaInfos=" + levelMetaInfos);

        //2. 执行其他level的compaction
        //TODO
    }

    /**
     * 判断一个sstable列表里是否包含对应编号的sstable文件
     * @param ssTables
     * @param fileNumber
     * @return
     */
    private boolean containTheSstable(List<SsTable> ssTables, Long fileNumber) {
        for (SsTable ssTable : ssTables) {
            if (fileNumber.equals(ssTable.getFileNumber())) {
                return true;
            }
        }

        return false;
    }

    /**
     * 合并sstable内容
     *
     * @param ssTableList
     * @param mergedData
     */
    private void mergeSstables(List<SsTable> ssTableList, ConcurrentSkipListMap<String, Command> mergedData) {
        ssTableList.forEach(ssTable -> {
            Map<String, JSONObject> jsonObjectMap = readSstableContent(ssTable);
            for (Map.Entry<String, JSONObject> entry : jsonObjectMap.entrySet()) {
                Command command = ConvertUtil.jsonToCommand(entry.getValue());
                //删除数据处理
                if (command instanceof RmCommand) {
                    mergedData.remove(command.getKey());
                } else {
                    mergedData.put(command.getKey(), command);
                }
            }
        });
    }

    /**
     * 读取sstable内容到内存
     * @param ssTable
     * @return
     * @throws IOException
     */
    private Map<String, JSONObject> readSstableContent(SsTable ssTable) {
        Map<String, JSONObject> jsonObjectMap = new HashMap<>();

        try {
            TreeMap<String, Position> sparseIndex = ssTable.getSparseIndex();
            for (Position position : sparseIndex.values()) {
                JSONObject jsonObject = BlockUtils.readJsonObject(position, true,
                        ssTable.getTableFile());
                //遍历每个key
                for (Map.Entry entry : jsonObject.entrySet()) {
                    String key = (String)entry.getKey();
                    jsonObjectMap.put(key, jsonObject.getJSONObject(key));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return jsonObjectMap;
    }

    /**
     * 查找存在重合key的sstable
     *
     * @param smallestKey 起始key
     * @param largestKey 终止key
     * @param ssTables 某层的sstale信息
     * @return
     */
    private List<SsTable> findOverlapSstables(String smallestKey, String largestKey,
                                              List<SsTable> ssTables) {
        List<SsTable> ssTableFileMetaInfos = new ArrayList<>();
        if (ssTables == null) {
            return ssTableFileMetaInfos;
        }

        for (SsTable ssTable : ssTables) {
            if (!(ssTable.getTableMetaInfo().getLargestKey().compareTo(smallestKey) < 0
                    || ssTable.getTableMetaInfo().getSmallestKey().compareTo(largestKey) > 0)) {
                ssTableFileMetaInfos.add(ssTable);
            }
        }

        return ssTableFileMetaInfos;
    }
}
