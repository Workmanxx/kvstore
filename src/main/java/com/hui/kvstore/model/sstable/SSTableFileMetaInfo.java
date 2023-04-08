package com.hui.kvstore.model.sstable;

import lombok.Data;

/**
 * sstable文件元数据信息
 */
@Data
public class SSTableFileMetaInfo {
    private final long fileNum;//文编号
    private final long fileSize;//文件大小
    private final String smallestKey;//最小的key
    private final String largestKey;//最大的key

    /**
     * 构造函数
     * @param fileSize
     * @param smallestKey
     * @param largestKey
     */
    public SSTableFileMetaInfo(long fileNum, long fileSize, String smallestKey, String largestKey) {
        this.fileNum = fileNum;
        this.fileSize = fileSize;
        this.smallestKey = smallestKey;
        this.largestKey = largestKey;
    }
}
