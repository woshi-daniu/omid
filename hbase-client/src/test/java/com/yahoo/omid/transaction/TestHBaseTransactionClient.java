package com.yahoo.omid.transaction;

import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

public class TestHBaseTransactionClient extends OmidTestBase {
    static final byte[] row1 = Bytes.toBytes("test-is-committed1");
    static final byte[] row2 = Bytes.toBytes("test-is-committed2");
    static final byte[] family = Bytes.toBytes(TEST_FAMILY);
    static final byte[] qualifier = Bytes.toBytes("testdata");
    static final byte[] data1 = Bytes.toBytes("testWrite-1");

    @Test
    public void testIsCommitted() throws Exception {
        TransactionManager tm = newTransactionManager();
        TTable table = new TTable(hbaseConf, TEST_TABLE);

        HBaseTransaction t1 = (HBaseTransaction) tm.begin();

        Put put = new Put(row1);
        put.add(family, qualifier, data1);
        table.put(t1, put);
        tm.commit(t1);

        HBaseTransaction t2 = (HBaseTransaction) tm.begin();
        put = new Put(row2);
        put.add(family, qualifier, data1);
        table.put(t2, put);
        table.getHTable().flushCommits();

        HBaseTransaction t3 = (HBaseTransaction) tm.begin();
        put = new Put(row2);
        put.add(family, qualifier, data1);
        table.put(t3, put);
        tm.commit(t3);

        HTable htable = new HTable(hbaseConf, TEST_TABLE);
        HBaseCellId hBaseCellId1 = new HBaseCellId(htable, row1, family, qualifier, t1.getStartTimestamp());
        HBaseCellId hBaseCellId2 = new HBaseCellId(htable, row2, family, qualifier, t2.getStartTimestamp());
        HBaseCellId hBaseCellId3 = new HBaseCellId(htable, row2, family, qualifier, t3.getStartTimestamp());

        HBaseTransactionClient hbaseTm = (HBaseTransactionClient) newTransactionManager();
        assertTrue("row1 should be committed", hbaseTm.isCommitted(hBaseCellId1));
        assertFalse("row2 should not be committed for kv2", hbaseTm.isCommitted(hBaseCellId2));
        assertTrue("row2 should be committed for kv3", hbaseTm.isCommitted(hBaseCellId3));
    }

    @Test
    public void testCrashAfterCommit() throws Exception {
        AbstractTransactionManager tm = spy((AbstractTransactionManager) newTransactionManager());
        // The following line emulates a crash after commit that is observed in (*) below
        doThrow(new RuntimeException()).when(tm).updateShadowCells(any(HBaseTransaction.class));

        TTable table = new TTable(hbaseConf, TEST_TABLE);

        HBaseTransaction t1 = (HBaseTransaction) tm.begin();

        // Test shadow cell are created properly
        Put put = new Put(row1);
        put.add(family, qualifier, data1);
        table.put(t1, put);
        try {
            tm.commit(t1);
        } catch (Exception e) { // (*) crash
            // Do nothing
        }

        assertTrue("Cell should be there",
                HBaseUtils.hasCell(table, row1, family, qualifier, t1.getStartTimestamp()));
        assertFalse("Shadow cell should not be there",
                HBaseUtils.hasShadowCell(table, row1, family,
                                              qualifier, t1.getStartTimestamp()));

        HTable htable = new HTable(hbaseConf, TEST_TABLE);
        HBaseCellId hBaseCellId = new HBaseCellId(htable, row1, family, qualifier, t1.getStartTimestamp());

        HBaseTransactionClient hbaseTm = (HBaseTransactionClient) newTransactionManager();
        assertTrue("row1 should be committed", hbaseTm.isCommitted(hBaseCellId));
    }

}