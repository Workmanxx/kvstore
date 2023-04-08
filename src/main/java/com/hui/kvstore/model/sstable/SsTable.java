package com.hui.kvstore.model.sstable;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.hui.kvstore.utils.BlockUtils;
import com.hui.kvstore.utils.FileUtils;
import com.hui.kvstore.utils.LoggerUtil;
import com.hui.kvstore.model.Position;
import com.hui.kvstore.model.command.Command;
import com.hui.kvstore.model.command.RmCommand;
import com.hui.kvstore.model.command.SetCommand;
import com.hui.kvstore.utils.ConvertUtil;
import lombok.Data;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xerial.snappy.Snappy;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * sstable
 */
@Data
public class SsTable implements Closeable {
    private final Logger LOGGER = LoggerFactory.getLogger(SsTable.class);

    public static final String RW = "rw";

    /**
     * sstable元数据信息
     *
     * TODO 这些信息应该保存在footer里
     */
    private TableMetaInfo tableMetaInfo;

    /**
     * 所处的level
     */
    private Integer level;

    /**
     * 数据块的稀疏索引
     */
    private TreeMap<String, Position> sparseIndex;

    /**
     * 文件句柄
     */
    private final RandomAccessFile tableFile;

    /**
     * 文件路径
     */
    private final String filePath;

    /**
     * 是否支持压缩。默认采用snappy压缩
     */
    private boolean enablePartDataCompress;

    private SsTable(String filePath, boolean enablePartDataCompress) {
        this.filePath = filePath;
        this.enablePartDataCompress = enablePartDataCompress;
        try {
            this.tableFile = new RandomAccessFile(filePath, RW);
            tableFile.seek(0);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /**
     *
     * @param fileNumber 文件编号
     * @param partSize 数据分区大小
     * @param enablePartDataCompress 是否开启压缩
     * @param level sstable位于哪一层
     */
    private SsTable(Long fileNumber, int partSize, boolean enablePartDataCompress, Integer level) {
        this.tableMetaInfo = new TableMetaInfo();
        this.tableMetaInfo.setNumber(fileNumber);
        this.tableMetaInfo.setPartSize(partSize);
        this.level = level;
        this.filePath = FileUtils.buildSstableFilePath(fileNumber, level);
        this.enablePartDataCompress = enablePartDataCompress;
        try {
            this.tableFile = new RandomAccessFile(filePath, RW);
            tableFile.seek(0);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
        sparseIndex = new TreeMap<>();
    }

    /**
     * 从mmetable构建ssTable
     * @param fileNumber 文件编号
     * @param partSize
     * @param index
     * @return
     */
    public static SsTable createFromIndex(Long fileNumber, int partSize,
                                          ConcurrentSkipListMap<String, Command> index,
                                          boolean enablePartDataCompress,
                                          Integer level) throws IOException {
        SsTable ssTable = new SsTable(fileNumber, partSize, enablePartDataCompress, level);
        ssTable.initFromIndex(index);
        return ssTable;
    }

    /**
     * 从文件中构建ssTable
     * @param filePath
     * @return
     */
    public static SsTable createFromFile(String filePath, boolean enablePartDataCompress) {
        SsTable ssTable = new SsTable(filePath, enablePartDataCompress);
        ssTable.restoreFromFile();
        return ssTable;
    }

    /**
     * 从ssTable中查询数据
     * 支持压缩的版本
     * @param key
     * @return
     */
    public Command query(String key) {
        try {
            LinkedList<Position> sparseKeyPositionList = new LinkedList<>();
            //从稀疏索引中找到最后一个小于key的位置，以及第一个大于key的位置
            for (String k : sparseIndex.keySet()) {
                if (k.compareTo(key) <= 0) {
                    sparseKeyPositionList.add(sparseIndex.get(k));
                } else {
                    break;
                }
            }
            if (sparseKeyPositionList.size() == 0) {
                return null;
            }
            LoggerUtil.debug(LOGGER, "[SsTable][restoreFromFile][sparseKeyPositionList]: {}", sparseKeyPositionList);
            //TODO 不同dataPart并不是按顺序存在内存上的呀？（但是不同dataPart之间的数据是按顺序的）
            //读取数据块的内容
            for (Position position : sparseKeyPositionList) {
                JSONObject dataPartJson = BlockUtils.readJsonObject(position, enablePartDataCompress, tableFile);
                LoggerUtil.debug(LOGGER, "[SsTable][restoreFromFile][dataPartJson]: {}", dataPartJson);
                if (dataPartJson.containsKey(key)) {
                    JSONObject value = dataPartJson.getJSONObject(key);
                    return ConvertUtil.jsonToCommand(value);
                }
            }
            return null;
        } catch (Throwable t) {
            t.printStackTrace();
            throw new RuntimeException(t);
        }
    }

    /**
     * 从文件中恢复ssTable到内存
     */
    private void restoreFromFile() {
        try {
            //先读取索引
            TableMetaInfo tableMetaInfo = TableMetaInfo.readFromFile(tableFile);
            LoggerUtil.debug(LOGGER, "[SsTable][restoreFromFile][tableMetaInfo]: {}", tableMetaInfo);
            //读取稀疏索引
            byte[] indexBytes = new byte[(int) tableMetaInfo.getIndexLen()];
            tableFile.seek(tableMetaInfo.getIndexStart());
            tableFile.read(indexBytes);
            String indexStr = new String(indexBytes, StandardCharsets.UTF_8);
            LoggerUtil.debug(LOGGER, "[SsTable][restoreFromFile][indexStr]: {}", indexStr);
            this.sparseIndex = JSONObject.parseObject(indexStr,
                    new TypeReference<TreeMap<String, Position>>() {
                    });
            tableMetaInfo.setNumber(FileUtils.parseFileNumber(filePath));
            this.tableMetaInfo = tableMetaInfo;
            this.level = FileUtils.parseSstableFileLevel(filePath);
            LoggerUtil.debug(LOGGER, "[SsTable][restoreFromFile][sparseIndex]: {}", sparseIndex);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /**
     * 从内存表转化为ssTable
     * @param index
     */
    private void initFromIndex(ConcurrentSkipListMap<String, Command> index) {
        try {
            JSONObject partData = new JSONObject(true);
            tableMetaInfo.setDataStart(tableFile.getFilePointer());
            for (Command command : index.values()) {
                //处理set命令
                if (command instanceof SetCommand) {
                    SetCommand set = (SetCommand) command;
                    partData.put(set.getKey(), set);
                }
                //处理rm命令
                if (command instanceof RmCommand) {
                    RmCommand rm = (RmCommand) command;
                    partData.put(rm.getKey(), rm);
                }

                //达到分段数量，开始写入数据段
                if (partData.size() >= tableMetaInfo.getPartSize()) {
                    writeDataPart(partData);
                }
            }
            //遍历完之后如果有剩余的数据（尾部数据不一定达到分段条件）写入文件
            if (partData.size() > 0) {
                writeDataPart(partData);
            }
            long dataPartLen = tableFile.getFilePointer() - tableMetaInfo.getDataStart();
            tableMetaInfo.setDataLen(dataPartLen);
            //保存稀疏索引
            byte[] indexBytes = JSONObject.toJSONString(sparseIndex).getBytes(StandardCharsets.UTF_8);
            tableMetaInfo.setIndexStart(tableFile.getFilePointer());
            tableFile.write(indexBytes);
            tableMetaInfo.setIndexLen(indexBytes.length);
            tableMetaInfo.setSmallestKey(index.firstKey());
            tableMetaInfo.setLargestKey(index.lastKey());
            tableMetaInfo.setFileSize(dataPartLen);
            LoggerUtil.debug(LOGGER, "[SsTable][initFromIndex][sparseIndex]: {}", sparseIndex);

            //保存文件索引
            tableMetaInfo.writeToFile(tableFile);
            LoggerUtil.info(LOGGER, "[SsTable][initFromIndex]: {},{}", filePath, tableMetaInfo);
        } catch (Throwable t) {
            LoggerUtil.debug(LOGGER, "initFromIndex exception,", t);
            throw new RuntimeException(t);
        }
    }

    /**
     * 将数据分区写入文件
     * @param partData
     * @throws IOException
     */
    private void writeDataPart(JSONObject partData) throws IOException {
        byte[] partDataBytes = partData.toJSONString().getBytes(StandardCharsets.UTF_8);

        //compress(use snappy: https://github.com/xerial/snappy-java)
        if (enablePartDataCompress) {
            //LoggerUtil.debug(LOGGER, "writeDataPart,partDataBytes size:[" + partDataBytes.length + "]");
            partDataBytes = Snappy.compress(partDataBytes);
            //LoggerUtil.debug(LOGGER, "writeDataPart,partDataBytes comressed size:[" + partDataBytes.length + "]");
        }

        long start = tableFile.getFilePointer();
        //LoggerUtil.debug(LOGGER, "writeDataPart,before write FilePointer=" + start);
        tableFile.write(partDataBytes);
        //LoggerUtil.debug(LOGGER, "writeDataPart,afteer write FilePointer=" + tableFile.getFilePointer());

        //记录数据段的第一个key到稀疏索引中
        Optional<String> firstKey = partData.keySet().stream().findFirst();
        byte[] finalPartDataBytes = partDataBytes;
        firstKey.ifPresent(s -> sparseIndex.put(s, new Position(start, finalPartDataBytes.length)));
        partData.clear();
    }

    @Override
    public void close() throws IOException {
        tableFile.close();
    }

    @Override
    public String toString() {
        Map<String, Object> result = new HashMap<>();
        result.put("tableMetaInfo", tableMetaInfo.toString());
        result.put("level", level);
        result.put("sparseIndex", sparseIndex.toString());
        result.put("filePath", filePath);
        return result.toString();
    }

    public Long getFileNumber() {
        return this.tableMetaInfo.getNumber();
    }
}
