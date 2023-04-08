package com.hui.kvstore.utils;

import com.alibaba.fastjson.JSONObject;
import com.hui.kvstore.model.Position;
import org.xerial.snappy.Snappy;

import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * 数据块操作工具类
 */
public class BlockUtils {

    /**
     * 从文件里读出数据块的内容
     *
     * @param position 数据块在文件中的内容
     * @param enablePartDataCompress 是否支持压缩
     * @param tableFile 文件句柄
     * @return
     */
    public final static JSONObject readJsonObject(Position position,
                                                  boolean enablePartDataCompress,
                                                  RandomAccessFile tableFile) throws IOException {
        //解压缩（因为压缩是每个part单独压缩的，所以需要每个part逐步解压缩）
        byte[] dataPart = new byte[(int) position.getLen()];
        tableFile.seek(position.getStart());
        tableFile.read(dataPart);

        if (enablePartDataCompress) {
            dataPart = Snappy.uncompress(dataPart);
        }
        JSONObject dataPartJson = JSONObject.parseObject(new String(dataPart));
        return dataPartJson;
    }
}
