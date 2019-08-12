/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.dtstack.flinkx.kudu.reader;

import com.dtstack.flinkx.inputformat.RichInputFormat;
import com.dtstack.flinkx.kudu.core.KuduConfig;
import com.dtstack.flinkx.kudu.core.KuduUtil;
import com.dtstack.flinkx.reader.MetaColumn;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.io.InputSplit;
import org.apache.flink.types.Row;
import org.apache.kudu.Type;
import org.apache.kudu.client.*;

import java.io.IOException;
import java.util.List;

/**
 * @author jiangbo
 * @date 2019/7/31
 */
public class KuduInputFormat extends RichInputFormat {

    protected List<MetaColumn> columns;

    protected KuduConfig kuduConfig;

    private transient KuduClient client;

    private transient KuduScanner scanner;

    private transient RowResultIterator iterator;

    @Override
    public void openInputFormat() throws IOException {
        super.openInputFormat();

        try {
            client = KuduUtil.getKuduClient(kuduConfig);
        } catch (IOException | InterruptedException e){
            throw new RuntimeException("Get KuduClient error", e);
        }
    }

    @Override
    protected void openInternal(InputSplit inputSplit) throws IOException {
        KuduTableSplit kuduTableSplit = (KuduTableSplit)inputSplit;
        scanner = KuduScanToken.deserializeIntoScanner(kuduTableSplit.getToken(), client);
    }

    @Override
    protected Row nextRecordInternal(Row row) throws IOException {
        row = new Row(columns.size());
        RowResult rowResult = iterator.next();

        for (int i = 0; i < columns.size(); i++) {
            MetaColumn column = columns.get(i);
            Type type = KuduUtil.getType(column.getType());
            if(column.getValue() != null){
                row.setField(i, KuduUtil.getValue(column.getValue(), type));
            } else {
                row.setField(i, getValue(type, rowResult, column.getName()));
            }
        }

        return row;
    }

    private Object getValue(Type type, RowResult rowResult, String name){
        Object objValue;

        if (Type.BOOL.equals(type)){
            objValue = rowResult.getBoolean(name);
        } else if(Type.INT8.equals(type)){
            objValue = rowResult.getByte(name);
        } else if(Type.INT16.equals(type)){
            objValue = rowResult.getShort(name);
        } else if(Type.INT32.equals(type)){
            objValue = rowResult.getInt(name);
        } else if(Type.INT64.equals(type)){
            objValue = rowResult.getLong(name);
        } else if(Type.FLOAT.equals(type)){
            objValue = rowResult.getFloat(name);
        } else if(Type.DOUBLE.equals(type)){
            objValue = rowResult.getDouble(name);
        } else if(Type.DECIMAL.equals(type)){
            objValue = rowResult.getDecimal(name);
        } else if(Type.UNIXTIME_MICROS.equals(type)){
            objValue = rowResult.getTimestamp(name);
        } else {
            objValue = rowResult.getString(name);
        }

        return objValue;
    }

    @Override
    public InputSplit[] createInputSplits(int minNumSplits) throws IOException {
        List<KuduScanToken> scanTokens = KuduUtil.getKuduScanToken(kuduConfig, columns, kuduConfig.getFilterString());
        KuduTableSplit[] inputSplits = new KuduTableSplit[scanTokens.size()];
        for (int i = 0; i < scanTokens.size(); i++) {
            inputSplits[i] = new KuduTableSplit(scanTokens.get(i).serialize(), i);
        }

        return inputSplits;
    }

    @Override
    public boolean reachedEnd() throws IOException {
        if(iterator == null || !iterator.hasNext()){
            return getNextRows();
        }

        return true;
    }

    private boolean getNextRows() throws IOException{
        if(scanner.hasMoreRows()){
            iterator = scanner.nextRows();
        }

        return iterator == null || !iterator.hasNext();
    }

    @Override
    protected void closeInternal() throws IOException {
        if(scanner != null){
            scanner.close();
            scanner = null;
        }
    }

    @Override
    public void closeInputFormat() throws IOException {
        super.closeInputFormat();

        if (client != null){
            client.close();
        }
    }

    @Override
    public void configure(Configuration parameters) {

    }
}
