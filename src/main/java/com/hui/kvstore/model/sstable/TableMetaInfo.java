package com.hui.kvstore.model.sstable;

import lombok.Data;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * ssTable元数据信息
 */
@Data
public class TableMetaInfo {

    /**
     * 版本号
     */
    private long version;

    /**
     * 文件编号
     */
    private long number;

    /**
     * 数据区开始
     */
    private long dataStart;

    /**
     * 数据区长度
     */
    private long dataLen;

    /**
     * 索引区开始
     */
    private long indexStart;

    /**
     * 索引区长度
     */
    private long indexLen;

    /**
     * 分段大小
     */
    private long partSize;

    private long fileSize;//文件大小
    private String smallestKey;//最小的key
    private String largestKey;//最大的key

    /**
     * 把数据写入到文件中
     * @param file
     */
    public void writeToFile(RandomAccessFile file) {
        try {
            //写入key的最小值和终止值
            file.writeBytes(smallestKey);
            file.writeInt(smallestKey.getBytes(StandardCharsets.UTF_8).length);
            file.writeBytes(largestKey);
            file.writeInt(largestKey.getBytes(StandardCharsets.UTF_8).length);

            file.writeLong(partSize);
            file.writeLong(dataStart);
            file.writeLong(dataLen);
            file.writeLong(indexStart);
            file.writeLong(indexLen);
            file.writeLong(version);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /**
     * 从文件中读取元信息，按照写入的顺序倒着读取出来
     * @param file
     * @return
     */
    public static TableMetaInfo readFromFile(RandomAccessFile file) {
        try {
            TableMetaInfo tableMetaInfo = new TableMetaInfo();
            long fileLen = file.length();

            file.seek(fileLen - 8);
            tableMetaInfo.setVersion(file.readLong());

            file.seek(fileLen - 8 * 2);
            tableMetaInfo.setIndexLen(file.readLong());

            file.seek(fileLen - 8 * 3);
            tableMetaInfo.setIndexStart(file.readLong());

            file.seek(fileLen - 8 * 4);
            tableMetaInfo.setDataLen(file.readLong());

            file.seek(fileLen - 8 * 5);
            tableMetaInfo.setDataStart(file.readLong());

            file.seek(fileLen - 8 * 6);
            tableMetaInfo.setPartSize(file.readLong());

            //读取key最小值和最大值
            file.seek(fileLen - 8 * 6 - 4);
            Integer largestKeyLength = file.readInt();
            file.seek(fileLen - 8 * 6 - 4 - largestKeyLength);
            byte[] largestKeyBytes = new byte[largestKeyLength];
            file.read(largestKeyBytes);
            tableMetaInfo.setLargestKey(new String(largestKeyBytes, StandardCharsets.UTF_8));

            file.seek(fileLen - 8 * 6 - 8 - largestKeyLength);
            Integer smallestKeyLength = file.readInt();
            file.seek(fileLen - 8 * 6 - 8 - largestKeyLength - smallestKeyLength);
            byte[] smallestKeyBytes = new byte[smallestKeyLength];
            file.read(smallestKeyBytes);
            tableMetaInfo.setSmallestKey(new String(smallestKeyBytes));

            return tableMetaInfo;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
