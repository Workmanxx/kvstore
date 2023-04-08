package com.hui.kvstore.model.sstable;

import com.hui.kvstore.model.command.Command;
import com.hui.kvstore.model.command.RmCommand;
import com.hui.kvstore.model.command.SetCommand;
import com.hui.kvstore.constants.KVConstants;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.ConcurrentSkipListMap;

public class SsTableTest {

    @Test
    public void createFromIndex() throws IOException {
        ConcurrentSkipListMap<String, Command> index = new ConcurrentSkipListMap<>();
        for (int i = 0; i < 10; i++) {
            SetCommand setCommand = new SetCommand("key" + i, "value" + i);
            index.put(setCommand.getKey(), setCommand);
        }
        index.put("key100", new SetCommand("key100", "value100"));
        index.put("key100", new RmCommand("key100"));
        SsTable ssTable = SsTable.createFromIndex(1L, 3, index, true, 0);
    }

    @Test
    public void query() {
        SsTable ssTable = SsTable.createFromFile(KVConstants.WORK_DIR + "1.sst", true);
        System.out.println(ssTable.query("key0"));
//        System.out.println(ssTable.query("key5"));
//        System.out.println(ssTable.query("key9"));
//        System.out.println(ssTable.query("key100"));
    }
}