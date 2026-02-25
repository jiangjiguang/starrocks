// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "formats/csv/map_converter.h"

#include <gtest/gtest.h>

#include "column/column_helper.h"
#include "formats/csv/converter.h"
#include "formats/csv/output_stream_string.h"
#include "runtime/types.h"

namespace starrocks::csv {

// NOLINTNEXTLINE
TEST(MapConverterTest, test_read_write_map_int_string) {
    // map<TINYINT,VARCHAR>
    TypeDescriptor t(TYPE_MAP);
    t.children.emplace_back(TYPE_TINYINT);
    t.children.emplace_back(TYPE_VARCHAR);
    t.children.back().len = 100;

    auto conv = csv::get_converter(t, false);
    auto col = ColumnHelper::create_column(t, false);
    // read
    EXPECT_TRUE(conv->read_string(col.get(), "{}", Converter::Options()));
    EXPECT_TRUE(conv->read_string(col.get(), "{1:\"abc\",2:NULL,3:\"\"}", Converter::Options()));
    EXPECT_TRUE(conv->read_string(col.get(), "{NULL:NULL}", Converter::Options()));
    EXPECT_TRUE(conv->read_string(col.get(), "{1:\"abc\",2:NULL,1:NULL}", Converter::Options())); // duplicated keys

    EXPECT_EQ(4, col->size());
    // {}
    EXPECT_EQ(0, col->get(0).get_map().size());
    EXPECT_EQ("{}", col->debug_item(0));
    // {1:abc,2:NULL,3:""}
    EXPECT_EQ(3, col->get(1).get_map().size());
    EXPECT_EQ("{1:'abc',2:NULL,3:''}", col->debug_item(1));
    // {NULL:NULL}
    EXPECT_EQ("{NULL:NULL}", col->debug_item(2));
    // {1:NULL,2:NULL}
    EXPECT_EQ(2, col->get(3).get_map().size());
    EXPECT_EQ("{2:NULL,1:NULL}", col->debug_item(3));

    // write
    csv::OutputStreamString buff;
    ASSERT_TRUE(conv->write_string(&buff, *col, 0, Converter::Options()).ok());
    ASSERT_TRUE(conv->write_string(&buff, *col, 1, Converter::Options()).ok());
    ASSERT_TRUE(conv->write_string(&buff, *col, 2, Converter::Options()).ok());
    ASSERT_TRUE(conv->write_string(&buff, *col, 3, Converter::Options()).ok());
    ASSERT_TRUE(buff.finalize().ok());
    ASSERT_EQ("{}{1:\"abc\",2:null,3:\"\"}{null:null}{2:null,1:null}", buff.as_string());
}

// NOLINTNEXTLINE
TEST(MapConverterTest, test_read_write_nest_map) {
    // map<int,map<TINYINT,VARCHAR>>
    TypeDescriptor n(TYPE_MAP);
    n.children.emplace_back(TYPE_TINYINT);
    n.children.emplace_back(TYPE_VARCHAR);
    n.children.back().len = 100;
    TypeDescriptor t(TYPE_MAP);
    t.children.emplace_back(TYPE_INT);
    t.children.push_back(n);

    auto conv = csv::get_converter(t, false);
    auto col = ColumnHelper::create_column(t, false);

    EXPECT_TRUE(conv->read_string(col.get(), "{}", Converter::Options()));
    EXPECT_TRUE(conv->read_string(col.get(), "{1:{}}", Converter::Options()));
    EXPECT_TRUE(conv->read_string(col.get(), "{1:{1:\"abc\",2:NULL},2:{3:\"\"}}", Converter::Options()));
    EXPECT_TRUE(conv->read_string(col.get(), "{NULL:{NULL:NULL}}", Converter::Options()));
    EXPECT_TRUE(conv->read_string(col.get(), "{NULL:{NULL:\"NULL\"},-20308764:{33:\"\"}}", Converter::Options()));
    EXPECT_TRUE(conv->read_string(col.get(), "{11:{1:\"abc\",2:NULL,1:NULL},22:{NULL:NULL},22:{2:\"unique\"}}",
                                  Converter::Options())); // duplicated keys

    EXPECT_EQ(6, col->size());
    // {}
    EXPECT_EQ(0, col->get(0).get_map().size());
    // {1:{}}
    EXPECT_EQ(1, col->get(1).get_map().size());
    EXPECT_EQ("{1:{}}", col->debug_item(1));
    // {1:{1:"abc",2:NULL},2:{3:""}}
    EXPECT_EQ(2, col->get(2).get_map().size());
    EXPECT_EQ("{1:{1:'abc',2:NULL},2:{3:''}}", col->debug_item(2));
    // {NULL:{NULL:NULL}}
    EXPECT_EQ("{NULL:{NULL:NULL}}", col->debug_item(3));
    // {NULL:{NULL:"NULL"},-20308764:{33:""}}
    EXPECT_EQ("{NULL:{NULL:'NULL'},-20308764:{33:''}}", col->debug_item(4));
    // {11:{1:"abc",2:NULL,1:NULL},22:{NULL:NULL},22:{2:"unique"}}
    EXPECT_EQ(2, col->get(5).get_map().size());
    EXPECT_EQ("{11:{2:NULL,1:NULL},22:{2:'unique'}}", col->debug_item(5));

    // write
    csv::OutputStreamString buff;
    ASSERT_TRUE(conv->write_string(&buff, *col, 0, Converter::Options()).ok());
    ASSERT_TRUE(conv->write_string(&buff, *col, 1, Converter::Options()).ok());
    ASSERT_TRUE(conv->write_string(&buff, *col, 2, Converter::Options()).ok());
    ASSERT_TRUE(conv->write_string(&buff, *col, 3, Converter::Options()).ok());
    ASSERT_TRUE(conv->write_string(&buff, *col, 4, Converter::Options()).ok());
    ASSERT_TRUE(conv->write_string(&buff, *col, 5, Converter::Options()).ok());
    ASSERT_TRUE(buff.finalize().ok());
    ASSERT_EQ(
            "{}{1:{}}{1:{1:\"abc\",2:null},2:{3:\"\"}}{null:{null:null}}{null:{null:\"NULL\"},-20308764:{33:\"\"}}{11:{"
            "2:null,1:null},22:{2:\"unique\"}}",
            buff.as_string());
}

// NOLINTNEXTLINE
TEST(MapConverterTest, test_read_hive_format_map_string_string) {
    // map<VARCHAR,VARCHAR> - Hive format
    TypeDescriptor t(TYPE_MAP);
    t.children.emplace_back(TYPE_VARCHAR);
    t.children.back().len = 100;
    t.children.emplace_back(TYPE_VARCHAR);
    t.children.back().len = 100;

    auto conv = csv::get_converter(t, false);
    auto col = ColumnHelper::create_column(t, false);

    // Set Hive options
    Converter::Options options;
    options.is_hive = true;
    options.array_format_type = ArrayFormatType::kHive;
    options.array_hive_collection_delimiter = '\002';
    options.array_hive_mapkey_delimiter = '\003';

    // Test empty map
    EXPECT_TRUE(conv->read_string(col.get(), "", options));

    // Test simple map: name\003John\002age\00325
    EXPECT_TRUE(conv->read_string(col.get(), "name\003John\002age\00325", options));

    // Test map with empty values: key1\003\002key2\003value2
    EXPECT_TRUE(conv->read_string(col.get(), "key1\003\002key2\003value2", options));

    // Test map with special characters: key\003value\002with\002\003delimiters\003\002
    EXPECT_TRUE(conv->read_string(col.get(), "key\003value\002with\002\003delimiters\003\002", options));

    // Test real-world example: script_version\0031.0\002label_name\003stuck_reference_frame
    EXPECT_TRUE(conv->read_string(col.get(), "script_version\0031.0\002label_name\003stuck_reference_frame", options));

    EXPECT_EQ(5, col->size());

    // Empty map
    EXPECT_EQ(0, col->get(0).get_map().size());

    // {name:John,age:25}
    EXPECT_EQ(2, col->get(1).get_map().size());
    EXPECT_EQ("{name:'John',age:'25'}", col->debug_item(1));

    // {key1:,key2:value2}
    EXPECT_EQ(2, col->get(2).get_map().size());
    EXPECT_EQ("{key1:'',key2:'value2'}", col->debug_item(2));

    // {key:value,with:\003delimiters:}
    EXPECT_EQ(3, col->get(3).get_map().size());
    EXPECT_EQ("{key:'value',with:'\003delimiters','':''}", col->debug_item(3));

    // {script_version:1.0,label_name:stuck_reference_frame}
    EXPECT_EQ(2, col->get(4).get_map().size());
    EXPECT_EQ("{script_version:'1.0',label_name:'stuck_reference_frame'}", col->debug_item(4));
}

// NOLINTNEXTLINE
TEST(MapConverterTest, test_read_hive_format_map_int_various_types) {
    // map<VARCHAR,VARIOUS_TYPES> - Hive format
    TypeDescriptor t(TYPE_MAP);
    t.children.emplace_back(TYPE_VARCHAR);
    t.children.back().len = 100;

    auto conv = csv::get_converter(t, false);
    auto col = ColumnHelper::create_column(t, false);

    // Set Hive options
    Converter::Options options;
    options.is_hive = true;
    options.array_format_type = ArrayFormatType::kHive;
    options.array_hive_collection_delimiter = '\002';
    options.array_hive_mapkey_delimiter = '\003';

    // Test with INT values: id\003123\002score\003456
    EXPECT_TRUE(conv->read_string(col.get(), "id\003123\002score\003456", options));

    // Test with boolean values: flag1\0031\002flag2\0030
    EXPECT_TRUE(conv->read_string(col.get(), "flag1\0031\002flag2\0030", options));

    // Test with decimal values: price\00312.34\002discount\0030.56
    EXPECT_TRUE(conv->read_string(col.get(), "price\00312.34\002discount\0030.56", options));

    EXPECT_EQ(3, col->size());

    // {id:123,score:456}
    EXPECT_EQ(2, col->get(0).get_map().size());
    EXPECT_EQ("{id:'123',score:'456'}", col->debug_item(0));

    // {flag1:1,flag2:0}
    EXPECT_EQ(2, col->get(1).get_map().size());
    EXPECT_EQ("{flag1:'1',flag2:'0'}", col->debug_item(1));

    // {price:12.34,discount:0.56}
    EXPECT_EQ(2, col->get(2).get_map().size());
    EXPECT_EQ("{price:'12.34',discount:'0.56'}", col->debug_item(2));
}

// NOLINTNEXTLINE
TEST(MapConverterTest, test_read_hive_vs_standard_format) {
    // map<STRING,STRING> - Compare Hive vs Standard format
    TypeDescriptor t(TYPE_MAP);
    t.children.emplace_back(TYPE_VARCHAR);
    t.children.back().len = 100;
    t.children.emplace_back(TYPE_VARCHAR);
    t.children.back().len = 100;

    auto conv = csv::get_converter(t, false);
    auto col_hive = ColumnHelper::create_column(t, false);
    auto col_standard = ColumnHelper::create_column(t, false);

    // Hive options
    Converter::Options hive_options;
    hive_options.is_hive = true;
    hive_options.array_format_type = ArrayFormatType::kHive;
    hive_options.array_hive_collection_delimiter = '\002';
    hive_options.array_hive_mapkey_delimiter = '\003';

    // Standard options
    Converter::Options standard_options;
    standard_options.is_hive = false;
    standard_options.array_format_type = ArrayFormatType::kDefault;

    // Same data in different formats
    const char* hive_data = "key1\003value1\002key2\003value2";
    const char* standard_data = "{key1:value1,key2:value2}";

    EXPECT_TRUE(conv->read_string(col_hive.get(), hive_data, hive_options));
    EXPECT_TRUE(conv->read_string(col_standard.get(), standard_data, standard_options));

    EXPECT_EQ(1, col_hive->size());
    EXPECT_EQ(1, col_standard->size());

    // Both should produce the same result
    EXPECT_EQ(col_hive->debug_item(0), col_standard->debug_item(0));
    EXPECT_EQ("{key1:'value1',key2:'value2'}", col_hive->debug_item(0));
}

// NOLINTNEXTLINE
TEST(MapConverterTest, test_read_hive_format_edge_cases) {
    // map<STRING,STRING> - Edge cases
    TypeDescriptor t(TYPE_MAP);
    t.children.emplace_back(TYPE_VARCHAR);
    t.children.back().len = 100;
    t.children.emplace_back(TYPE_VARCHAR);
    t.children.back().len = 100;

    auto conv = csv::get_converter(t, false);
    auto col = ColumnHelper::create_column(t, false);

    // Set Hive options
    Converter::Options options;
    options.is_hive = true;
    options.array_format_type = ArrayFormatType::kHive;
    options.array_hive_collection_delimiter = '\002';
    options.array_hive_mapkey_delimiter = '\003';

    // Test single key-value pair
    EXPECT_TRUE(conv->read_string(col.get(), "key\003value", options));

    // Test with empty key: \003value\002key2\003value2
    EXPECT_TRUE(conv->read_string(col.get(), "\003value\002key2\003value2", options));

    // Test with only collection delimiter (should result in empty map)
    EXPECT_TRUE(conv->read_string(col.get(), "\002", options));

    // Test malformed data (missing value after key)
    EXPECT_FALSE(conv->read_string(col.get(), "key1\003\002key2", options));

    EXPECT_EQ(3, col->size());

    // Single pair: {key:value}
    EXPECT_EQ(1, col->get(0).get_map().size());
    EXPECT_EQ("{key:'value'}", col->debug_item(0));

    // Empty key: {:value,key2:value2}
    EXPECT_EQ(2, col->get(1).get_map().size());
    EXPECT_EQ("{'':'value',key2:'value2'}", col->debug_item(1));

    // Empty map from malformed delimiter
    EXPECT_EQ(0, col->get(2).get_map().size());
}

// NOLINTNEXTLINE
TEST(MapConverterTest, test_read_hive_format_custom_delimiters) {
    // map<STRING,STRING> - Custom delimiters
    TypeDescriptor t(TYPE_MAP);
    t.children.emplace_back(TYPE_VARCHAR);
    t.children.back().len = 100;
    t.children.emplace_back(TYPE_VARCHAR);
    t.children.back().len = 100;

    auto conv = csv::get_converter(t, false);
    auto col = ColumnHelper::create_column(t, false);

    // Set Hive options with custom delimiters
    Converter::Options options;
    options.is_hive = true;
    options.array_format_type = ArrayFormatType::kHive;
    options.array_hive_collection_delimiter = '|';  // Custom collection delimiter
    options.array_hive_mapkey_delimiter = '=';      // Custom mapkey delimiter

    // Test with custom delimiters: key1=value1|key2=value2
    EXPECT_TRUE(conv->read_string(col.get(), "key1=value1|key2=value2", options));

    // Test with special characters: key=value|with|=|delimiters=value
    EXPECT_TRUE(conv->read_string(col.get(), "key=value|with|=|delimiters=value", options));

    EXPECT_EQ(2, col->size());

    // {key1:value1,key2:value2}
    EXPECT_EQ(2, col->get(0).get_map().size());
    EXPECT_EQ("{key1:'value1',key2:'value2'}", col->debug_item(0));

    // {key:value,with:=,delimiters:value}
    EXPECT_EQ(3, col->get(1).get_map().size());
    EXPECT_EQ("{key:'value',with:'=',delimiters:'value'}", col->debug_item(1));
}

// NOLINTNEXTLINE
TEST(MapConverterTest, test_read_hive_format_duplicated_keys) {
    // map<STRING,STRING> - Duplicated keys handling
    TypeDescriptor t(TYPE_MAP);
    t.children.emplace_back(TYPE_VARCHAR);
    t.children.back().len = 100;
    t.children.emplace_back(TYPE_VARCHAR);
    t.children.back().len = 100;

    auto conv = csv::get_converter(t, false);
    auto col = ColumnHelper::create_column(t, false);

    // Set Hive options
    Converter::Options options;
    options.is_hive = true;
    options.array_format_type = ArrayFormatType::kHive;
    options.array_hive_collection_delimiter = '\002';
    options.array_hive_mapkey_delimiter = '\003';

    // Test with duplicated keys (should keep first occurrence)
    EXPECT_TRUE(conv->read_string(col.get(), "key\003value1\002key\003value2\002key\003value3", options));

    // Test with multiple duplicated keys
    EXPECT_TRUE(conv->read_string(col.get(), "a\0031\002b\0032\002a\0033\002b\0034\002c\0035", options));

    EXPECT_EQ(2, col->size());

    // Only first key-value should be kept: {key:value1}
    EXPECT_EQ(1, col->get(0).get_map().size());
    EXPECT_EQ("{key:'value1'}", col->debug_item(0));

    // First occurrences should be kept: {a:1,b:2,c:5}
    EXPECT_EQ(3, col->get(1).get_map().size());
    EXPECT_EQ("{a:'1',b:'2',c:'5'}", col->debug_item(1));
}

} // namespace starrocks::csv
