package com.hui.kvstore.model.sstable;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 每一层的元数据信息
 */
@Data
public class LevelMetaInfo {
    /**
     * 层编号
     */
    private final Integer levelNo;
    /**
     * 包含的sstable文件元数据信息
     */
    private List<SSTableFileMetaInfo> ssTableFileMetaInfos = new ArrayList<>();

    public LevelMetaInfo(Integer levelNo){
        this.levelNo = levelNo;
    }
}
