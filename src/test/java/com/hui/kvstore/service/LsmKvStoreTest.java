package com.hui.kvstore.service;

import com.hui.kvstore.constants.KVConstants;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class LsmKvStoreTest {
    @Test
    public void set() throws IOException {
        //持久化阈值和数据分区大小为什么分别是4和3？
        KvStore kvStore = new LsmKvStore(KVConstants.WORK_DIR, 4, 3);
        for (int i = 1000; i < 1022; i++) {
            kvStore.set(i + "", i + "");
        }

        for (int i = 1000; i < 1011; i++) {
            assertEquals(i + "", kvStore.get(i + ""));
        }

         for (int i = 1000; i < 1011; i++) {
            kvStore.rm(i + "");
        }
        for (int i = 1000; i < 1011; i++) {
            assertNull(kvStore.get(i + ""));
        }

        kvStore.printfStats();

        /**
        kvStore.close();
        kvStore = new LsmKvStore(KVConstants.WORK_DIR, 4, 3);
        for (int i = 0; i < 11; i++) {
            assertNull(kvStore.get(i + ""));
        }
        kvStore.close();
         **/
    }

    /**
     * 测试数据库启动能正常初始化加载sstable内容
     *
     * 现在把set的逻辑执行，生成sstable。
     * 然后关闭数据库，把set的代码注释，启动数据库，可以发现能正常读取到数据。
     *
     * @throws Exception
     */
    @Test
    public void testInitSstables() throws Exception {
        KvStore kvStore = new LsmKvStore(KVConstants.WORK_DIR, 4, 3);

        //set的逻辑
        for(int i = 1;i <= 20;i++){
            kvStore.set(i + "", i + "");
        }

        //读取的逻辑
        for(int i = 1;i <= 10;i++){
            System.out.println(i + ",获取到了" + kvStore.get(i + ""));
        }
    }

    /**
     * 测试数据库能正常恢复wal log
     *
     * 先执行set逻辑，这里只写2条数据，发现数据不会被dump到sstable中
     * 然后关闭数据库，注释掉set逻辑，重新启动数据库，发现能正常吧wal log中的数据加载到mmtable
     * 能查询到
     * @throws Exception
     */
    @Test
    public void testRecoverWalLog() throws Exception {
        KvStore kvStore = new LsmKvStore(KVConstants.WORK_DIR, 4, 3);

        //set的逻辑
        for(int i = 1;i <= 2;i++){
            kvStore.set(i + "", i + "");
        }

        //读取的逻辑
        for(int i = 1;i <= 2;i++){
            System.out.println(i + ",获取到了" + kvStore.get(i + ""));
        }
    }
}