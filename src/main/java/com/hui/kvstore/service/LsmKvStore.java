package com.hui.kvstore.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.hui.kvstore.compaction.Compactioner;
import com.hui.kvstore.model.command.Command;
import com.hui.kvstore.model.command.RmCommand;
import com.hui.kvstore.model.command.SetCommand;
import com.hui.kvstore.constants.KVConstants;
import com.hui.kvstore.model.sstable.SsTable;
import com.hui.kvstore.utils.ConvertUtil;
import com.hui.kvstore.utils.LoggerUtil;
import lombok.Getter;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 基于LsmTree的KV数据库实现
 */
public class LsmKvStore implements KvStore {
    //LoggerFactory.getLogger可以在IDE控制台打印日志，便于开发，一般加在最上面
    private final Logger LOGGER = LoggerFactory.getLogger(LsmKvStore.class);

    public static final String WAL = "wal";
    public static final String RW_MODE = "rw";
    public static final String WAL_TMP = "walTmp";

    /**
     * memtable
     */
    private ConcurrentSkipListMap<String, Command> memtable;

    /**
     * immutable memtable
     */
    private ConcurrentSkipListMap<String, Command> immutableMemtable;

    /**
     * 维护每一层的sstable信息
     */
    @Getter
    private Map<Integer, List<SsTable>> levelMetaInfos = new ConcurrentHashMap<>();

    /**
     * 数据目录
     */
    private final String dataDir;

    /**
     * 读写锁
     */
    private final ReadWriteLock indexLock;

    /**
     * 持久化阈值
     */
    private final int storeThreshold;

    /**
     * 数据分区大小
     */
    private final int partSize;

    /**
     * 暂存数据的日志句柄
     */
    private RandomAccessFile wal;

    /**
     * 暂存数据日志文件
     */
    private File walFile;

    /**
     * 文件编号发号器
     */
    private final AtomicLong nextFileNumber = new AtomicLong(1);

    /**
     * compaction执行器
     */
    private Compactioner compactioner;

    /**
     * 初始化
     * @param dataDir 数据目录
     * @param storeThreshold 持久化阈值
     * @param partSize 数据分区大小
     */
    public LsmKvStore(String dataDir, int storeThreshold, int partSize) {
        try {
            this.dataDir = dataDir;
            this.storeThreshold = storeThreshold;
            this.partSize = partSize;
            //ReentrantReadWriteLock 并没有像ReentrantLock一样直接实现Lock 接口，而是内部分别持有ReadLock、WriteLock类型的成员变量，两者均实现了Lock 接口。
            this.indexLock = new ReentrantReadWriteLock();
            //dir是一个文件夹
            File dir = new File(dataDir);
            //files是dir内的所有子文件夹
            File[] files = dir.listFiles();
            //一层level，有一个包含多个sstable的集合：Map<Integer, List<SsTable>>   Integer是层号，List<SsTable>是sstable集合
            levelMetaInfos = new ConcurrentHashMap<>();
            memtable = new ConcurrentSkipListMap<>();
            //暂存数据的日志文件
            walFile = new File(dataDir + WAL);
            //暂存数据的日志句柄
            wal = new RandomAccessFile(dataDir + WAL, RW_MODE);
            compactioner = new Compactioner();

            //目录为空无需加载ssTable
            if (files == null || files.length == 0) {
                return;
            }

            //文件加载（sstable、wal log）
            for (File file : files) {
                String fileName = file.getName();
                //从暂存的WAL中恢复数据，一般是持久化ssTable过程中异常才会留下walTmp
                if (file.isFile() && fileName.equals(WAL_TMP)) {
                    restoreFromWal(new RandomAccessFile(file, RW_MODE));
                }
                //加载ssTable
                if (file.isFile() && fileName.endsWith(KVConstants.FILE_SUFFIX_SSTABLE)) {//以.sst为结尾的文件是sstable文件
                    SsTable ssTable = SsTable.createFromFile(file.getAbsolutePath(), true);
                    Integer level = ssTable.getLevel();

                    List<SsTable> tmpLevelSstables = null;
                    if (levelMetaInfos.get(level) == null) {
                        tmpLevelSstables = new ArrayList<>();
                        levelMetaInfos.put(level, tmpLevelSstables);
                    } else {
                        tmpLevelSstables = levelMetaInfos.get(level);
                    }
                    tmpLevelSstables.add(ssTable);
                } else if (file.isFile() && fileName.equals(WAL)) {
                    //加载WAL
                    walFile = file;
                    wal = new RandomAccessFile(file, RW_MODE);
                    restoreFromWal(wal);
                }
            }

            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("LsmKvStore started,levelMetaInfos=" + levelMetaInfos.toString());
            }
        } catch (Throwable t) {
            LOGGER.error("初始化异常~", t);
            throw new RuntimeException(t);
        }
    }

    /**
     * 从暂存日志中恢复数据
     * @param wal
     */
    private void restoreFromWal(RandomAccessFile wal) {
        try {
            long len = wal.length();
            long start = 0;
            wal.seek(start);
            while (start < len) {
                //先读取数据大小
                int valueLen = wal.readInt();
                //根据数据大小读取数据
                byte[] bytes = new byte[valueLen];
                wal.read(bytes);
                JSONObject value = JSON.parseObject(new String(bytes, StandardCharsets.UTF_8));
                Command command = ConvertUtil.jsonToCommand(value);
                if (command != null) {
                    memtable.put(command.getKey(), command);
                }
                start += 4;
                start += valueLen;
            }
            wal.seek(wal.length());
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    @Override
    public void set(String key, String value) {
        try {
            SetCommand command = new SetCommand(key, value);
            byte[] commandBytes = JSONObject.toJSONBytes(command);
            indexLock.writeLock().lock();
            //先保存数据到WAL中
            wal.writeInt(commandBytes.length);
            wal.write(commandBytes);
            memtable.put(key, command);

            //内存表大小超过阈值进行持久化
            if (memtable.size() > storeThreshold) {
                switchIndex();
                dumpToL0SsTable();
            }
        } catch (Throwable t) {
            t.printStackTrace();
            throw new RuntimeException(t);
        } finally {
            indexLock.writeLock().unlock();
        }
    }

    /**
     * 切换内存表，新建一个内存表，老的暂存起来
     */
    private void switchIndex() {
        try {
            indexLock.writeLock().lock();
            //切换内存表
            immutableMemtable = memtable;
            memtable = new ConcurrentSkipListMap<>();
            wal.close();
            //切换内存表后也要切换WAL
            File tmpWal = new File(dataDir + WAL_TMP);
            if (tmpWal.exists()) {
                if (!tmpWal.delete()) {
                    throw new RuntimeException("删除文件失败: walTmp");
                }
            }
            if (!walFile.renameTo(tmpWal)) {
                throw new RuntimeException("重命名文件失败: walTmp");
            }
            walFile = new File(dataDir + WAL);
            wal = new RandomAccessFile(walFile, RW_MODE);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        } finally {
            indexLock.writeLock().unlock();
        }
    }

    /**
     * 执行dump操作，保存数据到level0的ssTable
     */
    private void dumpToL0SsTable() {
        try {
            //获取文件编号
            Long fileNumber = nextFileNumber.getAndIncrement();
            //ssTable命名按照编号递增
            SsTable ssTable = SsTable.createFromIndex(fileNumber, partSize, immutableMemtable, true, 0);

            //sstable信息记录
            List<SsTable> levelSstables = levelMetaInfos.get(0);
            if (levelSstables == null) {
                levelSstables = new ArrayList<>();
                levelMetaInfos.put(0, levelSstables);
            }
            levelSstables.add(ssTable);

            //持久化完成删除暂存的内存表和WAL_TMP
            immutableMemtable = null;
            File tmpWal = new File(dataDir + WAL_TMP);
            if (tmpWal.exists()) {
                if (!tmpWal.delete()) {
                    throw new RuntimeException("删除文件失败: walTmp");
                }
            }

            //可能会触发compaction
            compactioner.compaction(levelMetaInfos, nextFileNumber);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    @Override
    public String get(String key) {
        LoggerUtil.info(LOGGER, "[get]key: {}", key);
        try {
            indexLock.readLock().lock();
            //先从memtable中取
            Command command = memtable.get(key);
            //再尝试从不可变索引中取，此时可能处于持久化sstable的过程中
            if (command == null && immutableMemtable != null) {
                command = immutableMemtable.get(key);
            }
            if (command == null) {
                //memtable中没有尝试从ssTable中获取，从新的ssTable找到老的
                command = findFromSstables(key);
            }

            if (command instanceof SetCommand) {
                return ((SetCommand) command).getValue();
            }
            if (command instanceof RmCommand) {
                return null;
            }
            //找不到说明不存在
            return null;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        } finally {
            indexLock.readLock().unlock();
        }
    }

    /**
     * 从sstable中查找
     *
     * @param key key值
     * @return
     */
    private Command findFromSstables(String key) {
        //1. 查找level0
        Command l0Result = findFromL0Sstables(key);
        if (l0Result != null) {
            return l0Result;
        }

        //2. 查找其他level
        for (int level = 1;level < KVConstants.SSTABLE_MAX_LEVEL;++level) {
            Command otherLevelResult = findFromOtherLevelSstables(key, level);
            if (otherLevelResult != null) {
                return otherLevelResult;
            }
        }
        return null;
    }

    /**
     * 在level0查找
     *
     * level0因为不同sstable之间存在相同key信息，因此需要逐个遍历
     *
     * @param key
     * @return
     */
    private Command findFromL0Sstables(String key) {
        List<SsTable> l0Sstables = levelMetaInfos.get(0);
        if (CollectionUtils.isEmpty(l0Sstables)) {
            return null;
        }

        //按编号从大到小排列
        l0Sstables.sort((sstabl1, sstable2) -> {
            if (sstabl1.getFileNumber() < sstable2.getFileNumber()) {
                return 1;
            } else if (sstabl1.getFileNumber() == sstable2.getFileNumber()) {
                return 0;
            } else {
                return -1;
            }
        });

        //存在多个key相同的数据时，以最新的为准
        for (SsTable ssTable : l0Sstables) {
            Command command = ssTable.query(key);
            if (command != null) {
                return command;
            }
        }

        return null;
    }

    /**
     * 在其他level查找
     *
     * 其他level的sstable之间不会存在key重叠的情况，因此可以采用二分查找
     *
     * @param key
     * @param level 层编号
     * @return
     */
    private Command findFromOtherLevelSstables(String key, Integer level) {
        List<SsTable> sstables = levelMetaInfos.get(level);
        if (CollectionUtils.isEmpty(sstables)) {
            return null;
        }

        //二分查找
        //按编号从小到大排列
        sstables.sort((sstabl1, sstable2) -> {
            if (sstabl1.getTableMetaInfo().getSmallestKey().compareTo(sstable2.getTableMetaInfo().getSmallestKey()) < 0) {
                return -1;
            } else if (sstabl1.getTableMetaInfo().getSmallestKey().compareTo(sstable2.getTableMetaInfo().getSmallestKey()) == 0) {
                return 0;
            } else {
                return 1;
            }
        });

        Command command = binarySearchSstables(key, sstables);
        if (command != null) {
            return command;
        }

        return null;
    }

    /**
     * 二分查找
     *
     * @param key
     * @param ssTables
     * @return
     */
    private Command binarySearchSstables(String key, List<SsTable> ssTables) {
        if (ssTables == null) {
            return null;
        }

        int left = 0, right = ssTables.size() - 1;
        while (left <= right) {
            int mid = left + (right - left) / 2;
            SsTable midSsTable = ssTables.get(mid);
            if (key.compareTo(midSsTable.getTableMetaInfo().getSmallestKey()) >= 0
                && key.compareTo(midSsTable.getTableMetaInfo().getLargestKey()) <= 0) {
                return midSsTable.query(key);
            } else if (key.compareTo(midSsTable.getTableMetaInfo().getSmallestKey()) < 0) {
                right = mid - 1;
            } else {
                left = mid + 1;
            }
        }

        return null;
    }

    @Override
    public void rm(String key) {
        try {
            //删除和写入的操作是一样的
            indexLock.writeLock().lock();
            RmCommand rmCommand = new RmCommand(key);
            byte[] commandBytes = JSONObject.toJSONBytes(rmCommand);
            wal.writeInt(commandBytes.length);
            wal.write(commandBytes);
            memtable.put(key, rmCommand);
            if (memtable.size() > storeThreshold) {
                switchIndex();
                dumpToL0SsTable();
            }
        } catch (Throwable t) {
            throw new RuntimeException(t);
        } finally {
            indexLock.writeLock().unlock();
        }
    }

    @Override
    public void close() throws IOException {
        wal.close();
        for (List<SsTable> ssTableList : levelMetaInfos.values()) {
            ssTableList.forEach(ssTable -> {
                try {
                    ssTable.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @Override
    public void printfStats() {
        LoggerUtil.debug(LOGGER, "printfStats,levelMetaInfos=" + this.getLevelMetaInfos());
    }
}
