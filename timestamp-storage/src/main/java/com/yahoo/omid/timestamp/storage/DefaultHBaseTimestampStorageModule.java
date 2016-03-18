/**
 * Copyright 2011-2016 Yahoo Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.yahoo.omid.timestamp.storage;

import com.google.inject.AbstractModule;
import com.google.inject.name.Names;
import com.yahoo.omid.HBaseConfigModule;

import static com.yahoo.omid.timestamp.storage.HBaseTimestampStorageConfig.DEFAULT_TIMESTAMP_STORAGE_CF_NAME;
import static com.yahoo.omid.timestamp.storage.HBaseTimestampStorageConfig.DEFAULT_TIMESTAMP_STORAGE_TABLE_NAME;

/**
 * This class is instantiated by the yaml parser.
 * Snake_yaml needs POJO style class to work properly with all the setters and getters.
 */
public class DefaultHBaseTimestampStorageModule extends AbstractModule {
    private String tableName = DEFAULT_TIMESTAMP_STORAGE_TABLE_NAME;
    private String familyName = DEFAULT_TIMESTAMP_STORAGE_CF_NAME;
    private String keytab;
    private String principal;

    @Override
    public void configure() {
        bindConstant().annotatedWith(Names.named(DEFAULT_TIMESTAMP_STORAGE_TABLE_NAME)).to(tableName);
        bindConstant().annotatedWith(Names.named(DEFAULT_TIMESTAMP_STORAGE_CF_NAME)).to(familyName);
        install(new HBaseConfigModule(principal, keytab));
        install(new HBaseTimestampStorageModule());
    }

    // ----------------------------------------------------------------------------------------------------------------
    // WARNING: Do not remove getters/setters, needed by snake_yaml!
    // ----------------------------------------------------------------------------------------------------------------

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getFamilyName() {
        return familyName;
    }

    public void setFamilyName(String familyName) {
        this.familyName = familyName;
    }

    public String getPrincipal() {
        return principal;
    }

    public void setPrincipal(String principal) {
        this.principal = principal;
    }

    public String getKeytab() {
        return keytab;
    }

    public void setKeytab(String keytab) {
        this.keytab = keytab;
    }
}
