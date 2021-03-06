package vendor;

import main.Column;
import main.Table;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import main.Schema;

public class Oracle implements Vendor {

	private final static Logger LOGGER = Logger.getLogger(Schema.class.getName());
        
	public void getTableStatistics(String databaseName, String schemaName, List<Table> tables, Connection connection) throws SQLException {
		String query = "select TABLE_NAME, NUM_ROWS from ALL_TABLES where OWNER = '" + schemaName + "'";

		Map<String, Table> map = new HashMap<>();
		for (Table table : tables) {
			map.put(table.getName(), table);
		}

		try (Statement stmt = connection.createStatement();
		     ResultSet rs = stmt.executeQuery(query)) {
			while (rs.next()) {
				Table table = map.get(rs.getString(1));
				if (table == null) continue;
				for (Column column : table.getColumnList()) {
					if (column == null) continue;
					column.setEstimatedRowCount(rs.getInt(2));
				}
			}
		}

		LOGGER.info("Table statistics collected successfully");
	}

	// Note when comparing to financial dataset from MySQL:
	// 	1) Oracle treats empty strings as nulls (at least during import)... -> order.k_symbol contains nulls on Oracle
	//	2) All numbers are ported as numeric(*) -> numbers are not constrained and detection of decimals does not work
	public void getColumnStatistics(String databaseName, String schemaName, List<Table> tables, Connection connection) throws SQLException {
                
                boolean create_procedure_priv_granded = false;
                String query = "SELECT * FROM USER_SYS_PRIVS WHERE PRIVILEGE='CREATE PROCEDURE'";
                try (Statement stmt = connection.createStatement();
		     ResultSet rs = stmt.executeQuery(query)) {
			if (rs.next()) create_procedure_priv_granded = true;
                }
                
                // Avoid defining custom functions
                // https://mwidlake.wordpress.com/2010/01/03/decoding-high_value-and-low_value/
                String decodeLow = "decode(data_type " +
                                ",'NUMBER'       ,to_char(utl_raw.cast_to_number(low_value)) " +
                                ",'CHAR'         ,to_char(utl_raw.cast_to_varchar2(low_value)) " + 
                                ",'VARCHAR2'     ,to_char(utl_raw.cast_to_varchar2(low_value)) " + 
                                ",'NVARCHAR2'    ,to_char(utl_raw.cast_to_nvarchar2(low_value)) " +
                                ",'BINARY_DOUBLE',to_char(utl_raw.cast_to_binary_double(low_value)) " +
                                ",'BINARY_FLOAT' ,to_char(utl_raw.cast_to_binary_float(low_value)) " +
                                ",'DATE'         ,rtrim(" +
                                "ltrim(to_char(100*(to_number(substr(low_value,1,2),'XX')-100)" +
                                "+ (to_number(substr(low_value,3,2),'XX')-100),'0000'))||'.'||" +
                                "ltrim(to_char(to_number(substr(low_value,5,2),'XX'),'00'))||'.'||" +
                                "ltrim(to_char(to_number(substr(low_value,7,2),'XX'),'00'))||'.'||" +
                                "ltrim(to_char(to_number(substr(low_value,9,2),'XX')-1,'00'))||'.'||" +
                                "ltrim(to_char(to_number(substr(low_value,11,2),'XX')-1,'00'))||'.'||" +
                                "ltrim(to_char(to_number(substr(low_value,13,2),'XX')-1,'00')))" +
                                ", low_value)";
                
                String decodeHigh = "decode(data_type " +
                                ",'NUMBER'       ,to_char(utl_raw.cast_to_number(high_value)) " +
                                ",'CHAR'         ,to_char(utl_raw.cast_to_varchar2(high_value)) " + 
                                ",'VARCHAR2'     ,to_char(utl_raw.cast_to_varchar2(high_value)) " + 
                                ",'NVARCHAR2'    ,to_char(utl_raw.cast_to_nvarchar2(high_value)) " +
                                ",'BINARY_DOUBLE',to_char(utl_raw.cast_to_binary_double(high_value)) " +
                                ",'BINARY_FLOAT' ,to_char(utl_raw.cast_to_binary_float(high_value)) " +
                                ",'DATE'         ,rtrim(" +
                                "ltrim(to_char(100*(to_number(substr(high_value,1,2),'XX')-100)" +
                                "+ (to_number(substr(high_value,3,2),'XX')-100),'0000'))||'.'||" +
                                "ltrim(to_char(to_number(substr(high_value,5,2),'XX'),'00'))||'.'||" +
                                "ltrim(to_char(to_number(substr(high_value,7,2),'XX'),'00'))||'.'||" +
                                "ltrim(to_char(to_number(substr(high_value,9,2),'XX')-1,'00'))||'.'||" +
                                "ltrim(to_char(to_number(substr(high_value,11,2),'XX')-1,'00'))||'.'||" +
                                "ltrim(to_char(to_number(substr(high_value,13,2),'XX')-1,'00')))" +
                                ", high_value)";
                
                // Create raw->data_type conversion stored functions.
		// See:
		// https://jonathanlewis.wordpress.com/2006/11/29/low_value-high_value/
		// https://ardentperf.com/2013/11/19/convert-rawhex-to-timestamp/
		// http://dbcrusade.blogspot.cz/2008/08/oracle-resolving-ora-29275-partial.html
		// NOTE: It is unpleasing that we have to have a privilege to create the stored functions.
                if (create_procedure_priv_granded) {
                    query = "create or replace function raw_to_num(i_raw raw)\n" +
                                    "return number\n" +
                                    "as\n" +
                                    "    m_n number;\n" +
                                    "begin\n" +
                                    "    dbms_stats.convert_raw_value(i_raw,m_n);\n" +
                                    "    return m_n;\n" +
                                    "end;\n" +
                                    "  ";

                    try (Statement stmt = connection.createStatement()) {
                            stmt.execute(query);
                    }

                    query = "create or replace function raw_to_date(i_raw raw)\n" +
                                    "return date\n" +
                                    "as\n" +
                                    "    m_n date;\n" +
                                    "begin\n" +
                                    "    dbms_stats.convert_raw_value(i_raw,m_n);\n" +
                                    "    return m_n;\n" +
                                    "end;\n" +
                                    " ";

                    try (Statement stmt = connection.createStatement()) {
                            stmt.execute(query);
                    }

                    query = "create or replace function raw_to_varchar2(i_raw raw)\n" +
                                    "return varchar2\n" +
                                    "as\n" +
                                    "    m_n varchar2(40);\n" +
                                    "begin\n" +
                                    "    dbms_stats.convert_raw_value(i_raw,m_n);\n" +
                                    "    return m_n;\n" +
                                    "end;\n" +
                                    " ";

                    try (Statement stmt = connection.createStatement()) {
                            stmt.execute(query);
                    }

                    query = "create or replace function raw_to_nvarchar2(i_raw raw)\n" +
                                    "return nvarchar2\n" +
                                    "as\n" +
                                    "    m_n nvarchar2(20);\n" +
                                    "begin\n" +
                                    "    dbms_stats.convert_raw_value_nvarchar(i_raw,m_n);\n" +
                                    "    return m_n;\n" +
                                    "end;\n" +
                                    " ";

                    try (Statement stmt = connection.createStatement()) {
                            stmt.execute(query);
                    }

                    query = "create or replace function raw_to_float(i_raw raw)\n" +
                                    "return float\n" +
                                    "as\n" +
                                    "    m_n float;\n" +
                                    "begin\n" +
                                    "    dbms_stats.convert_raw_value(i_raw,m_n);\n" +
                                    "    return m_n;\n" +
                                    "end;\n" +
                                    "";

                    try (Statement stmt = connection.createStatement()) {
                            stmt.execute(query);
                    }

                    query = "create or replace function raw_to_timestamp(p_str in VARCHAR2)\n" +
                                    "return timestamp\n" +
                                    "as\n" +
                                    "begin\n" +
                                    "\t\t\t\treturn to_timestamp(\n" +
                                    "        to_char( to_number( substr( p_str, 1, 2 ), 'xx' ) - 100, 'fm00' ) ||\n" +
                                    "        to_char( to_number( substr( p_str, 3, 2 ), 'xx' ) - 100, 'fm00' ) ||\n" +
                                    "        to_char( to_number( substr( p_str, 5, 2 ), 'xx' ), 'fm00' ) ||\n" +
                                    "        to_char( to_number( substr( p_str, 7, 2 ), 'xx' ), 'fm00' ) ||\n" +
                                    "        to_char( to_number( substr( p_str,9, 2 ), 'xx' )-1, 'fm00' ) ||\n" +
                                    "        to_char( to_number( substr( p_str,11, 2 ), 'xx' )-1, 'fm00' ) ||\n" +
                                    "        to_char( to_number( substr( p_str,13, 2 ), 'xx' )-1, 'fm00' ), 'yyyymmddhh24miss' );\n" +
                                    "end;\n" +
                                    " ";

                    try (Statement stmt = connection.createStatement()) {
                            stmt.execute(query);
                    }



                    // All to string
                    decodeLow = "case \n" +
                                    "\t\t\t\t\t\t\t\twhen data_type = 'NVARCHAR2' then to_char(raw_to_nvarchar2(low_value))\n" +
                                    "                when data_type = 'VARCHAR2' then to_char(raw_to_varchar2(low_value)||'')\n" +
                                    "\t\t\t\t\t\t\t\twhen data_type = 'CHAR' then to_char(raw_to_varchar2(low_value))\n" +
                                    "                when data_type = 'DATE' then to_char(raw_to_date(low_value), 'YYYYMMDD')\n" +
                                    "                when data_type = 'NUMBER' then to_char(raw_to_num(low_value))\n" +
                                    "\t\t\t\t\t\t\t\twhen data_type = 'BINARY_FLOAT' then to_char(raw_to_float(low_value))\n" +
                                    "\t\t\t\t\t\t\t\twhen data_type like 'TIMESTAMP%' then to_char(raw_to_timestamp(low_value), 'YYYYMMDDHH24MISS')\n" +
                                    " \t\t\t\t\t\t\t\telse to_char(raw_to_nvarchar2(low_value))\n" +
                                    "\t\t\t\tend";
                    decodeHigh = "case \n" +
                                    "\t\t\t\t\t\t\t\twhen data_type = 'NVARCHAR2' then to_char(raw_to_nvarchar2(high_value))\n" +
                                    "                when data_type = 'VARCHAR2' then to_char(raw_to_varchar2(high_value)||'')\n" +
                                    "\t\t\t\t\t\t\t\twhen data_type = 'CHAR' then to_char(raw_to_varchar2(high_value))\n" +
                                    "                when data_type = 'DATE' then to_char(raw_to_date(high_value), 'YYYYMMDD')\n" +
                                    "                when data_type = 'NUMBER' then to_char(raw_to_num(high_value))\n" +
                                    "\t\t\t\t\t\t\t\twhen data_type = 'BINARY_FLOAT' then to_char(raw_to_float(high_value))\n" +
                                    "\t\t\t\t\t\t\t\twhen data_type like 'TIMESTAMP%' then to_char(raw_to_timestamp(high_value), 'YYYYMMDDHH24MISS')\n" +
                                    " \t\t\t\t\t\t\t\telse to_char(raw_to_nvarchar2(high_value))\n" +
                                    "\t\t\t\tend";
                }
                
		query = "select TABLE_NAME, COLUMN_NAME, NUM_DISTINCT, " + decodeLow + " LOW_VALUE, " + decodeHigh + " HIGH_VALUE, NUM_NULLS, AVG_COL_LEN from SYS.ALL_TAB_COLUMNS where OWNER = '" + schemaName + "'";
		
		Map<String, Table> tableMap = new HashMap<>();
		for (Table table : tables) {
			tableMap.put(table.getName(), table);
		}

		try (Statement stmt = connection.createStatement();
		     ResultSet rs = stmt.executeQuery(query)) {
			while (rs.next()) {
				Table table = tableMap.get(rs.getString(1));
				if (table == null) continue;

				Column column = table.getColumn(rs.getString(2));
				column.setUniqueRatio(column.getRowCount() == 0 ? 0 : rs.getDouble(3) / column.getRowCount());
				column.setTextMin(rs.getString(4));
				column.setTextMax(rs.getString(5));
				column.setNullRatio(column.getRowCount() == 0 ? 0 :rs.getDouble(6) / column.getRowCount());
				column.setWidthAvg(rs.getDouble(7));
			}
		}

        LOGGER.info("Column statistics collected successfully");
		// Output quality control (if something turns sour, we want to know about that)
		QualityControl.qcNumericalValues(tables);
	}
}
