package com.hui.kvstore.utils;

import com.hui.kvstore.constants.KVConstants;
import org.apache.commons.lang3.StringUtils;

/**
 * 文件相关工具类
 */
public class FileUtils {
    /**
     * 构建sstable文件全路径
     *
     * @param fileNumber 文件编号
     * @param level 所处的level
     * @return
     */
    public final static String buildSstableFilePath(Long fileNumber, Integer level) {
        return KVConstants.WORK_DIR + level + "_" + fileNumber + KVConstants.FILE_SUFFIX_SSTABLE;
    }

    /**
     * 根据sstable文件全路径解析文件编号
     *
     * @param filePath
     * @return
     */
    public final static Long parseFileNumber(String filePath) {
        if (StringUtils.isBlank(filePath)) {
            return -99L;
        }

        Integer lastIndex = filePath.lastIndexOf('/');
        if (lastIndex == -1) {
            return -99L;
        }

        String fileName = filePath.substring(lastIndex + 1);
        return Long.valueOf(fileName.substring(fileName.indexOf("_") + 1, fileName.indexOf('.')));
    }

    /**
     * 根据sstable文件全路径解析所处的level
     *
     * @param filePath
     * @return
     */
    public final static Integer parseSstableFileLevel(String filePath) {
        if (StringUtils.isBlank(filePath)) {
            return -99;
        }

        Integer lastIndex = filePath.lastIndexOf('/');
        if (lastIndex == -1) {
            return -99;
        }

        String fileName = filePath.substring(lastIndex + 1);
        return Integer.valueOf(fileName.substring(0, fileName.indexOf('_')));
    }
}
