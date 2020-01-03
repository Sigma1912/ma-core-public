/*
 *  Copyright (C) 2013 Deltamation Software. All rights reserved.
 *  @author Jared Wiltshire
 */
package com.serotonin.m2m2.db.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.Select;
import org.jooq.SelectConnectByStep;
import org.jooq.SelectJoinStep;
import org.jooq.SelectLimitStep;
import org.jooq.SelectSelectStep;
import org.jooq.SortField;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.infiniteautomation.mango.db.query.ConditionSortLimit;
import com.infiniteautomation.mango.db.query.Index;
import com.infiniteautomation.mango.db.query.JoinClause;
import com.infiniteautomation.mango.db.query.QueryAttribute;
import com.infiniteautomation.mango.db.query.RQLToCondition;
import com.infiniteautomation.mango.db.query.SQLQueryColumn;
import com.infiniteautomation.mango.db.query.TableModel;
import com.infiniteautomation.mango.monitor.AtomicIntegerMonitor;
import com.infiniteautomation.mango.spring.events.DaoEvent;
import com.infiniteautomation.mango.spring.events.DaoEventType;
import com.serotonin.ModuleNotLoadedException;
import com.serotonin.ShouldNeverHappenException;
import com.serotonin.db.MappedRowCallback;
import com.serotonin.db.pair.IntStringPair;
import com.serotonin.log.LogStopWatch;
import com.serotonin.m2m2.Common;
import com.serotonin.m2m2.db.DatabaseProxy.DatabaseType;
import com.serotonin.m2m2.i18n.TranslatableMessage;
import com.serotonin.m2m2.vo.AbstractBasicVO;
import com.serotonin.m2m2.vo.permission.PermissionHolder;

import net.jazdw.rql.parser.ASTNode;

/**
 * Provides an API to retrieve, update and save VO objects from and to the
 * database.
 *
 * Copyright (C) 2013 Deltamation Software. All Rights Reserved.
 *
 * @author Jared Wiltshire
 */
public abstract class AbstractBasicDao<T extends AbstractBasicVO> extends BaseDao implements AbstractBasicVOAccess<T> {
    protected Log LOG = LogFactory.getLog(AbstractBasicDao.class);

    public static final int DEFAULT_LIMIT = 100;

    public static final String WHERE = " WHERE ";
    public static final String OR = " OR ";
    public static final String AND = " AND ";
    public static final String LIMIT = " LIMIT ";

    private final ObjectMapper mapper;
    protected final ApplicationEventPublisher eventPublisher;

    /**
     * Map UI or Model member names to the Database Column Names. They will get
     * translated when the query is generated
     */
    protected final Map<String, IntStringPair> propertiesMap;

    /**
     * Map of Database Column Names to Column SQL Type
     */
    protected final LinkedHashMap<String, Integer> propertyTypeMap;

    /**
     * List of our Joins
     */
    protected final List<JoinClause> joins;

    /**
     * List of our Indexes
     */
    protected final List<Index> indexes;

    /*
     * SQL templates
     */
    protected final Field<Object> PK_COLUMN;

    
    protected final String TABLE_PREFIX; // Without ending .
    protected final String SELECT_ALL_BASE; // Without location of FROM
    protected final String SELECT_ALL;
    protected final String SELECT_ALL_SORT;
    protected final String SELECT_ALL_FIXED_SORT;
    protected final String SELECT_BY_ID;
    protected final String SELECT_BY_XID;
    protected final String SELECT_BY_NAME;
    protected final String SELECT_XID_BY_ID;
    protected final String SELECT_ID_BY_XID;
    protected final String INSERT;
    protected final String UPDATE;
    protected final String DELETE;
    protected final String COUNT_BASE;
    protected final String COUNT;
    // protected final String EXTRA_SQL;

    public final String tableName;

    protected int[] updateStatementPropertyTypes; // Required for Derby LOBs
    protected int[] insertStatementPropertyTypes; // Required for Derby LOBs

    protected TableModel tableModel;

    public final String tablePrefix; // Prefix with dot table.

    protected final String pkColumn;

    //Monitor for count of table
    protected final AtomicIntegerMonitor countMonitor;

    protected final Table<? extends Record> table;
    protected final Name tableAlias;
    protected final Table<? extends Record> joinedTable;
    protected final List<Field<?>> fields;
    protected final Map<String, Field<Object>> propertyToField;
    protected final Map<String, Function<Object, Object>> valueConverterMap;
    protected final RQLToCondition rqlToCondition;

    /**
     * @param tablePrefix -  Provide a table prefix to use for complex queries. Ie. Joins Do not include the . at the end of the prefix
     * @param extraProperties - Other SQL for use in Queries
     * @param tablePrefix
     * @param extraProperties
     * @param mapper
     * @param publisher
     */
    public AbstractBasicDao(String tablePrefix, String[] extraProperties, ObjectMapper mapper, ApplicationEventPublisher publisher) {
        this(tablePrefix, extraProperties, false, null, mapper, publisher);
    }

    /**
     * @param tablePrefix -  Provide a table prefix to use for complex queries. Ie. Joins Do not include the . at the end of the prefix
     * @param extraProperties - Other SQL for use in Queries
     * @param useSubQuery - Compute and use subqueries for performance
     * @param countMonitorName - If not null create a monitor to track table row count
     * @param mapper
     * @param publisher
     */
    public AbstractBasicDao(String tablePrefix, String[] extraProperties,boolean useSubQuery, 
            TranslatableMessage countMonitorName,
            ObjectMapper mapper, ApplicationEventPublisher publisher) {
        
        TABLE_PREFIX = tablePrefix;
        
        if (tablePrefix != null)
            this.tablePrefix = tablePrefix + ".";
        else
            this.tablePrefix = "";

        this.tableName = getTableName();

        this.table = DSL.table(DSL.name(this.tableName));
        this.tableAlias = DSL.name(TABLE_PREFIX);

        Map<String, IntStringPair> propMap = getPropertiesMap();
        if (propMap == null)
            this.propertiesMap = new HashMap<String, IntStringPair>();
        else
            this.propertiesMap = propMap;

        LinkedHashMap<String, Integer> propTypeMap = getPropertyTypeMap();
        if (propTypeMap == null)
            throw new ShouldNeverHappenException("Property Type Map is required!");
        else
            this.propertyTypeMap = propTypeMap;
        
        this.mapper = mapper;
        this.eventPublisher = publisher;

        // generate SQL statements
        String selectAll = "SELECT ";
        String insert = "INSERT INTO " + tableName + " (";
        String insertValues = "";
        String update = "UPDATE " + tableName + " SET ";

        // Map of properties to their QueryAttribute
        Map<String, QueryAttribute> attributeMap = new HashMap<String, QueryAttribute>();

        fields = new ArrayList<>();

        Set<String> properties = this.propertyTypeMap.keySet();
        // don't the first property - "id", in the insert statements
        int i = 0;
        for (String prop : properties) {
            // Add this attribute
            QueryAttribute attribute = new QueryAttribute();
            attribute.setColumnName(this.tablePrefix + prop);
            attribute.addAlias(prop);
            attribute.setSqlType(this.propertyTypeMap.get(prop));
            attributeMap.put(prop, attribute);

            String selectPrefix = (i == 0) ? this.tablePrefix : "," + this.tablePrefix;
            selectAll += selectPrefix + prop;

            String insertPrefix = (i == 1) ? "" : ",";
            if (i >= 1) {
                insert += insertPrefix + prop;
                insertValues += insertPrefix + "?";
                update += insertPrefix + prop + "=?";
            }
            i++;

            fields.add(DSL.field(DSL.name(TABLE_PREFIX, prop)));
        }

        if (extraProperties != null) {
            for (String prop : extraProperties) {
                selectAll += "," + prop;
                String[] split = prop.split("\\.");
                fields.add(DSL.field(DSL.name(split)));
            }
        }

        this.propertyToField = this.createPropertyToField();
        this.valueConverterMap = this.createValueConverterMap();
        this.rqlToCondition = this.createRqlToCondition();

        // Setup the Joins
        this.joins = getJoins();

        String joinedTableSql = this.tableName + " AS " + TABLE_PREFIX;
        String joinSql = null;

        if (!this.joins.isEmpty()) {
            StringBuilder joinSqlBuilder = new StringBuilder();
            for (JoinClause join : this.joins) {
                joinSqlBuilder.append(join.toString());
            }
            joinSql = joinSqlBuilder.toString();
            joinedTableSql += joinSql;
        }

        this.joinedTable = DSL.table(joinedTableSql);

        this.pkColumn = getPkColumnName();
        if (StringUtils.isEmpty(pkColumn)) {
            PK_COLUMN = DSL.field(DSL.name(TABLE_PREFIX, "id"));
        }else {
            PK_COLUMN = DSL.field(DSL.name(TABLE_PREFIX, getPkColumnName()));
        }

        // Add the table prefix to the queries if necessary
        SELECT_ALL_BASE = selectAll + " FROM ";
        if (this.tablePrefix.equals("")) {
            if (StringUtils.isEmpty(pkColumn)) {
                COUNT_BASE = "SELECT COUNT(*) FROM ";
            }else {
                COUNT_BASE = "SELECT COUNT(DISTINCT " + pkColumn + ") FROM ";
            }

            if (joinSql != null)
                SELECT_ALL = selectAll + " FROM " + tableName + joinSql;
            else
                SELECT_ALL = selectAll + " FROM " + tableName;

            SELECT_ALL_SORT = SELECT_ALL + " ORDER BY ";
            if (properties.contains("name")) {
                SELECT_ALL_FIXED_SORT = SELECT_ALL + " ORDER BY name ASC";
            } else {
                SELECT_ALL_FIXED_SORT = SELECT_ALL + " ORDER BY id ASC";
            }

            SELECT_BY_ID = SELECT_ALL + " WHERE id=?";
            SELECT_BY_XID = SELECT_ALL + " WHERE " + getXidColumnName() + "=?";
            SELECT_BY_NAME = SELECT_ALL + " WHERE name=?";
            SELECT_XID_BY_ID = "SELECT " + getXidColumnName() + " FROM " + tableName + " WHERE id=?";
            SELECT_ID_BY_XID = "SELECT id FROM " + tableName + " WHERE " + getXidColumnName() + "=?";
            INSERT = insert + ") VALUES (" + insertValues + ")";
            UPDATE = update + " WHERE id=?";
            DELETE = "DELETE FROM " + tableName + " WHERE id=?";

            if (joinSql != null) {
                if (StringUtils.isEmpty(pkColumn))
                    COUNT = "SELECT COUNT(*) FROM " + tableName + joinSql;
                else
                    COUNT = "SELECT COUNT(DISTINCT " + pkColumn + ") FROM " + tableName + joins;
            } else {
                if (StringUtils.isEmpty(pkColumn))
                    COUNT = "SELECT COUNT(*) FROM " + tableName;
                else
                    COUNT = "SELECT COUNT(DISTINCT " + pkColumn + ") FROM " + tableName;
            }

        } else {
            // this.tablePrefix will end in a . where the local tablePrefix
            // shouldn't
            if (StringUtils.isEmpty(pkColumn))
                COUNT_BASE = "SELECT COUNT(*) FROM ";
            else
                COUNT_BASE = "SELECT COUNT(DISTINCT " + this.tablePrefix + pkColumn + ") FROM ";

            if (joinSql != null)
                SELECT_ALL = selectAll + " FROM " + tableName + " AS " + tablePrefix + joinSql;
            else
                SELECT_ALL = selectAll + " FROM " + tableName + " AS " + tablePrefix;

            SELECT_ALL_SORT = SELECT_ALL + " ORDER BY ";
            if (properties.contains("name")) {
                SELECT_ALL_FIXED_SORT = SELECT_ALL + " ORDER BY " + this.tablePrefix + "name ASC";
            } else {
                SELECT_ALL_FIXED_SORT = SELECT_ALL + " ORDER BY " + this.tablePrefix + "id ASC";
            }

            SELECT_BY_ID = SELECT_ALL + " WHERE " + this.tablePrefix + "id=?";
            SELECT_BY_XID = SELECT_ALL + " WHERE " + this.tablePrefix + getXidColumnName() + "=?";
            SELECT_BY_NAME = SELECT_ALL + " WHERE " + this.tablePrefix + "name=?";
            SELECT_XID_BY_ID = "SELECT " + this.tablePrefix + getXidColumnName() + " FROM " + tableName + " " + tablePrefix + " WHERE " + this.tablePrefix + "id=?";
            SELECT_ID_BY_XID = "SELECT " + this.tablePrefix + "id FROM " + tableName + " " + tablePrefix + " WHERE " + this.tablePrefix + getXidColumnName() + "=?";
            INSERT = insert + ") VALUES (" + insertValues + ")";
            UPDATE = update + " WHERE id=?";
            DELETE = "DELETE FROM " + tableName + " WHERE id=?";
            if (joinSql != null) {
                if (StringUtils.isEmpty(pkColumn))
                    COUNT = "SELECT COUNT(*) FROM " + tableName + " AS " + tablePrefix + joinSql;
                else
                    COUNT = "SELECT COUNT(DISTINCT " + this.tablePrefix + pkColumn + ") FROM " + tableName + " AS "
                            + tablePrefix + joinSql;
            } else {
                if (StringUtils.isEmpty(pkColumn))
                    COUNT = "SELECT COUNT(*) FROM " + tableName + " AS " + tablePrefix;
                else
                    COUNT = "SELECT COUNT(DISTINCT " + this.tablePrefix + pkColumn + ") FROM " + tableName + " AS "
                            + tablePrefix;
            }
        }

        // Create the Update and Insert property types lists
        if ((getPkColumnName() != null) && (this.propertyTypeMap.get(getPkColumnName()) != null)) {
            this.updateStatementPropertyTypes = new int[this.propertyTypeMap.size()];
            this.insertStatementPropertyTypes = new int[this.propertyTypeMap.size() - 1];
        } else {
            this.updateStatementPropertyTypes = new int[this.propertyTypeMap.size()];
            this.insertStatementPropertyTypes = new int[this.propertyTypeMap.size()];
        }

        Iterator<String> it = this.propertyTypeMap.keySet().iterator();
        int j = 0;
        while (it.hasNext()) {
            String property = it.next();
            if (!property.equals(getPkColumnName())) {
                Integer type = this.propertyTypeMap.get(property);
                this.updateStatementPropertyTypes[j] = type;
                this.insertStatementPropertyTypes[j] = type;
                j++;
            }
        }

        if ((getPkColumnName() != null) && (this.propertyTypeMap.get(getPkColumnName()) != null)) {
            Integer pkType = this.propertyTypeMap.get(getPkColumnName());
            this.updateStatementPropertyTypes[j] = pkType;
            attributeMap.put(getPkColumnName(), new QueryAttribute(getPkColumnName(), new HashSet<String>(), pkType));
        }

        Iterator<String> propertyMapIterator = this.propertiesMap.keySet().iterator();
        while (propertyMapIterator.hasNext()) {
            String propertyName = propertyMapIterator.next();
            IntStringPair pair = this.propertiesMap.get(propertyName);

            QueryAttribute attribute = attributeMap.get(pair.getValue());
            if (attribute != null) {
                attribute.addAlias(propertyName);
            } else {
                QueryAttribute newAttribute = new QueryAttribute();
                newAttribute.setColumnName(pair.getValue());
                newAttribute.setSqlType(pair.getKey());
                newAttribute.addAlias(propertyName);
                attributeMap.put(propertyName, newAttribute);
            }
        }

        // Collect the indexes
        this.indexes = getIndexes();

        // Create the model
        this.tableModel = new TableModel(this.getTableName(), new ArrayList<QueryAttribute>(attributeMap.values()));

        //Setup Monitors
        if(countMonitorName != null) {
            this.countMonitor = Common.MONITORED_VALUES.create(this.getClass().getCanonicalName() + ".COUNT")
                    .name(countMonitorName)
                    .value(this.count())
                    .uploadToStore(true)
                    .buildAtomic();
        }else{
            this.countMonitor = null;
        }
    }

    /**
     * Override as necessary Can be null if no Pk Exists
     *
     * @return String name of Pk Column
     */
    public String getPkColumnName() {
        return "id";
    }
    
    /**
     * Override as necessary
     * @return
     */
    public String getXidColumnName() {
        return "xid";
    }

    /**
     * Gets the table name that the Dao operates on
     *
     * @return table name
     */
    protected abstract String getTableName();

    /**
     * Converts a VO object into an array of objects for insertion or updating
     * of database
     *
     * @param vo
     *            to convert
     * @return object array
     */
    protected abstract Object[] voToObjectArray(T vo);

    /**
     * Both the properties and the type maps must be setup
     *
     * @return
     */
    protected abstract LinkedHashMap<String, Integer> getPropertyTypeMap();

    /**
     * Condition required for user to have read permission.  Override as required, note 
     *  that when overriding the user can and will sometimes be null
     * @param user - reading user (can be null)
     * @return
     */
    protected Condition hasReadPermission(PermissionHolder user) {
        return DSL.trueCondition();
    }
    
    /**
     * Returns a map which maps a virtual property to a real one used for
     * sorting/filtering from the database e.g. dateFormatted -> timestamp
     *
     * @return map of properties
     */
    protected Map<String, IntStringPair> getPropertiesMap() {
        return null;
    }

    /**
     * Gets the row mapper for converting the retrieved database values into a
     * VO object
     *
     * @return row mapper
     */
    public abstract RowMapper<T> getRowMapper();

    /**
     * Get a map of the Joined tables with table prefix --> Join SQL To be
     * overridden as necessary
     *
     * @return
     */
    protected List<JoinClause> getJoins() {
        return new ArrayList<JoinClause>();
    }

    /**
     * Optionally return a list of Indexes for this table for Query optimization
     *
     * @return
     */
    protected List<Index> getIndexes() {
        return new ArrayList<Index>();
    }

    @Override
    public boolean delete(int id) {
        return delete(get(id));
    }

    @Override
    public boolean delete(T vo) {
        if (vo != null) {
            Integer deleted = (Integer) getTransactionTemplate().execute(status -> {
                deleteRelationalData(vo);
                return ejt.update(DELETE, vo.getId());
            });
            
            if(this.countMonitor != null)
                this.countMonitor.addValue(-deleted);
            if(deleted > 0) {
                this.publishEvent(createDaoEvent(DaoEventType.DELETE, vo, vo));
            }
            return deleted > 0;
        }
        return false;
    }

    @Override
    public void deleteRelationalData(T vo) { }
    
    @Override
    public void insert(T vo) {
        getTransactionTemplate().execute(status -> {
            int id = -1;
            if (insertStatementPropertyTypes == null)
                id = ejt.doInsert(INSERT, voToObjectArray(vo));
            else
                id = ejt.doInsert(INSERT, voToObjectArray(vo), insertStatementPropertyTypes);
            vo.setId(id);
            saveRelationalData(vo, true);
            return null;
        });
        
        this.publishEvent(createDaoEvent(DaoEventType.CREATE, vo, null));

        if (this.countMonitor != null)
            this.countMonitor.increment();
    }

    @Override
    public void saveRelationalData(T vo, boolean insert) { }

    @Override
    public void update(int id, T vo) {
        update(get(id), vo);
    }
    
    @Override
    public void update(T existing, T vo) {
        getTransactionTemplate().execute(status -> {
            List<Object> list = new ArrayList<>();
            list.addAll(Arrays.asList(voToObjectArray(vo)));
            list.add(vo.getId());
    
            if (updateStatementPropertyTypes == null)
                ejt.update(UPDATE, list.toArray());
            else
                ejt.update(UPDATE, list.toArray(), updateStatementPropertyTypes);
            saveRelationalData(vo, false);
            return null;
        });
        this.publishEvent(createDaoEvent(DaoEventType.UPDATE, vo, existing));
    }
    
    @Override
    public T get(int id) {
        Select<Record> query = this.create.select(this.fields)
                .from(this.joinedTable)
                .where(PK_COLUMN.eq(id))
                .limit(1);
        String sql = query.getSQL();
        List<Object> args = query.getBindValues();
        T item = this.queryForObject(sql, args.toArray(new Object[args.size()]), getRowMapper(), null);
        if (item != null) {
            loadRelationalData(item);
        }
        return item;
    }
    
    @Override
    public void getAll(MappedRowCallback<T> callback) {
        PermissionHolder user = Common.getUser();
        Select<Record> query = this.create.select(this.fields)
                .from(this.joinedTable)
                .where(this.hasReadPermission(user));
        String sql = query.getSQL();
        List<Object> args = query.getBindValues();
        query(sql, args.toArray(), getCallbackResultSetExtractor((item, index) -> {
            loadRelationalData(item);
            callback.row(item, index);
        }));
    }
    
    @Override
    public List<T> getAll() {
        List<T> items = new ArrayList<>();
        getAll((item, index) -> {
            items.add(item);
        });
        return items;
    }

    @Override
    public void loadRelationalData(T vo) { }
    
    @Override
    public int count() {
        if (StringUtils.isEmpty(pkColumn)) {
            return this.create.fetchCount(table);
        }else {
            return this.create.select(DSL.countDistinct(PK_COLUMN)).from(this.tableName + " AS " + TABLE_PREFIX).fetchOneInto(Integer.class);
        }
    }

    /**
     * @return
     */
    public TableModel getTableModel() {
        return tableModel;
    }

    /**
     * @param argument
     * @return
     */
    public SQLQueryColumn getQueryColumn(String prop) {
        boolean mapped = false;

        Set<String> properties = this.propertyTypeMap.keySet();
        String dbCol = prop;

        Integer sqlType;
        if (propertiesMap.containsKey(prop)) {
            IntStringPair pair = propertiesMap.get(prop);
            dbCol = pair.getValue();
            sqlType = pair.getKey();
            mapped = true;
        } else {
            sqlType = this.propertyTypeMap.get(dbCol);
        }

        if (mapped || properties.contains(dbCol)) {
            if (mapped)
                return new SQLQueryColumn(dbCol, sqlType);
            else
                return new SQLQueryColumn(this.tablePrefix + dbCol, sqlType);
        }

        // No Column matches...
        throw new ShouldNeverHappenException("No column found for: " + prop);
    }

    /**
     * Helper to prepare a statement
     *
     * @param sql
     * @param args
     * @return
     * @throws SQLException
     */
    public PreparedStatement createPreparedStatement(String sql, List<Object> args, boolean stream) throws SQLException {

        PreparedStatement stmt;
        if(stream){
            if(this.databaseType == DatabaseType.MYSQL){
                stmt = this.dataSource.getConnection().prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                stmt.setFetchSize(Integer.MIN_VALUE);
            }else{
                //TODO Choose settings for other types to stream
                stmt = this.dataSource.getConnection().prepareStatement(sql);
            }
        }else{
            stmt = this.dataSource.getConnection().prepareStatement(sql);
            int fetchSize = Common.envProps.getInt("db.fetchSize", -1);
            if(fetchSize > 0)
                stmt.setFetchSize(fetchSize);
        }

        int index = 1;
        for (Object o : args) {
            stmt.setObject(index, o);
            index++;
        }

        return stmt;
    }

    public AtomicIntegerMonitor getCountMonitor(){
        return this.countMonitor;
    }

    /**
     * Get the Select statement up to just before the FROM 'table'
     * @return
     */
    public String getSelectBaseSql(){
        return SELECT_ALL_BASE;
    }

    /**
     * Get the Select All Statment to be used with row mapper
     * @return
     */
    public String getSelectAllSql(){
        return SELECT_ALL;
    }

    /**
     * Get the Insert SQL Statement
     * @return
     */
    public String getInsertSql(){
        return INSERT;
    }

    /**
     * Get the Update SQL Statement
     * @return
     */
    public String getUpdateSql(){
        return UPDATE;
    }

    public String getDeleteSql(){
        return DELETE;
    }

    public String getCountBaseSql(){
        return COUNT_BASE;
    }

    /**
     * Get the count statment
     * @return
     */
    public String getCountSql(){
        return COUNT;
    }

    public <R extends Record> SelectJoinStep<R> joinTables(SelectJoinStep<R> select, ConditionSortLimit conditions) {
        return select;
    }

    @Override
    public int customizedCount(ConditionSortLimit conditions) {
        Condition condition = conditions.getCondition();
        SelectSelectStep<Record1<Integer>> count;
        if (StringUtils.isNotEmpty(this.pkColumn)) {
            count = this.create.select(DSL.countDistinct(DSL.field(tableAlias.append(this.pkColumn))));
        } else {
            count = this.create.selectCount();
        }

        SelectJoinStep<Record1<Integer>> select;
        if (condition == null) {
            select = count.from(this.table.as(tableAlias));
        } else {
            select = count.from(this.joinedTable);
            select = joinTables(select, conditions);
        }
        return customizedCount(select, condition);
    }

    protected int customizedCount(SelectJoinStep<Record1<Integer>> input, Condition condition) {
        Select<Record1<Integer>> select = input;
        if (condition != null) {
            select = input.where(condition);
        }

        String sql = select.getSQL();
        List<Object> arguments = select.getBindValues();
        Object[] argumentsArray = arguments.toArray(new Object[arguments.size()]);

        LogStopWatch stopWatch = null;
        if (useMetrics) {
            stopWatch = new LogStopWatch();
        }

        int count = this.ejt.queryForInt(sql, argumentsArray, 0);

        if (stopWatch != null) {
            stopWatch.stop("customizedCount(): " + this.create.renderInlined(select), metricsThreshold);
        }

        return count;
    }

    @Override
    public void customizedQuery(ConditionSortLimit conditions, MappedRowCallback<T> callback) {
        SelectJoinStep<Record> select = this.create.select(this.fields).from(this.joinedTable);
        select = joinTables(select, conditions);
        customizedQuery(select, conditions.getCondition(), conditions.getSort(), conditions.getLimit(), conditions.getOffset(), callback);
    }

    protected void customizedQuery(SelectJoinStep<Record> select, Condition condition, List<SortField<Object>> sort, Integer limit, Integer offset, MappedRowCallback<T> callback) {
        SelectConnectByStep<Record> afterWhere = condition == null ? select : select.where(condition);
        SelectLimitStep<Record> afterSort = sort == null ? afterWhere : afterWhere.orderBy(sort);

        Select<Record> offsetStep = afterSort;
        if (limit != null) {
            if (offset != null) {
                offsetStep = afterSort.limit(offset, limit);
            } else {
                offsetStep = afterSort.limit(limit);
            }
        }

        String sql = offsetStep.getSQL();
        List<Object> arguments = offsetStep.getBindValues();
        Object[] argumentsArray = arguments.toArray(new Object[arguments.size()]);

        LogStopWatch stopWatch = null;
        if (useMetrics) {
            stopWatch = new LogStopWatch();
        }

        this.query(sql, argumentsArray, getCallbackResultSetExtractor(callback));

        if (stopWatch != null) {
            stopWatch.stop("customizedQuery(): " + this.create.renderInlined(offsetStep), metricsThreshold);
        }
    }

    protected Map<String, Field<Object>> createPropertyToField() {
        Map<String, Field<Object>> propertyToField = new HashMap<>(propertyTypeMap.size() + propertiesMap.size());

        for (Entry<String, Integer> e : propertyTypeMap.entrySet()) {
            String property = e.getKey();
            propertyToField.put(property, DSL.field(this.tableAlias.append(property)));
        }

        for (Entry<String, IntStringPair> e : propertiesMap.entrySet()) {
            String name = e.getValue().getValue();
            propertyToField.put(e.getKey(), DSL.field(DSL.name(name.split("\\."))));
        }

        return propertyToField;
    }

    protected Map<String, Function<Object, Object>> createValueConverterMap() {
        return this.getPropertyTypeMap().entrySet().stream()
                .filter(e -> e.getValue() == Types.CHAR)
                .collect(Collectors.toMap(Map.Entry::getKey, e -> RQLToCondition.BOOLEAN_VALUE_CONVERTER));
    }

    protected RQLToCondition createRqlToCondition() {
        return new RQLToCondition(this.propertyToField, this.valueConverterMap);
    }

    @Override
    public ConditionSortLimit rqlToCondition(ASTNode rql) {
        return this.rqlToCondition.visit(rql);
    }

    public void rqlQuery(ASTNode rql, MappedRowCallback<T> callback) {
        ConditionSortLimit result = this.rqlToCondition(rql);
        this.customizedQuery(result, callback);
    }

    public int rqlCount(ASTNode rql) {
        ConditionSortLimit result = this.rqlToCondition(rql);
        return this.customizedCount(result);
    }

    protected DaoEvent<T> createDaoEvent(DaoEventType type, T vo, T existing) {
        return new DaoEvent<T>(this, type, vo, null);
    }
    
    protected void publishEvent(DaoEvent<T> event) {
        if (this.eventPublisher != null) {
            this.eventPublisher.publishEvent(event);
        }
    }

    /**
     * Get a writer for serializing JSON
     * @return
     */
    public ObjectWriter getObjectWriter(Class<?> type) {
        return mapper.writerFor(type);
    }

    /**
     * Get a reader for use de-serializing JSON
     * @return
     */
    public ObjectReader getObjectReader(Class<?> type) {
        return mapper.readerFor(type);
    }
    
    /**
     * Available to overload the result set extractor for list queries
     * @param callback
     * @return
     */
    protected ResultSetExtractor<List<T>> getListResultSetExtractor() {
        return getListResultSetExtractor((e,rs) -> {
            if(e.getCause() instanceof ModuleNotLoadedException)
                LOG.error(e.getCause().getMessage(), e.getCause());
            else
                LOG.error(e.getMessage(), e);
        });
    }
    
    /**
     * 
     * @param error
     * @return
     */
    protected ResultSetExtractor<List<T>> getListResultSetExtractor(BiConsumer<Exception, ResultSet> error) {
        return new ResultSetExtractor<List<T>>() {

            @Override
            public List<T> extractData(ResultSet rs) throws SQLException, DataAccessException {
                RowMapper<T> rowMapper = getRowMapper();
                List<T> results = new ArrayList<>();
                int rowNum = 0;
                while (rs.next()) {
                    try {
                        results.add(rowMapper.mapRow(rs, rowNum));
                    }catch (Exception e) {
                        error.accept(e, rs);
                    }finally {
                        rowNum++;
                    }
                }
                return results;
            }
        };
    }

    /**
     * Available to overload the result set extractor for callback queries 
     *  to customize error handling
     * @param callback
     * @return
     */
    protected ResultSetExtractor<Void> getCallbackResultSetExtractor(MappedRowCallback<T> callback) {
       return getCallbackResultSetExtractor(callback, (e, rs) -> {
           if(e.getCause() instanceof ModuleNotLoadedException)
               LOG.error(e.getCause().getMessage(), e.getCause());
           else
               LOG.error(e.getMessage(), e);
       }); 
    }
    
    /**
     * 
     * @param callback
     * @param error
     * @return
     */
    protected ResultSetExtractor<Void> getCallbackResultSetExtractor(MappedRowCallback<T> callback, BiConsumer<Exception, ResultSet> error) {
        return new ResultSetExtractor<Void>() {

             @Override
             public Void extractData(ResultSet rs) throws SQLException, DataAccessException {
                 RowMapper<T> rowMapper = getRowMapper();
                 int rowNum = 0;
                 while (rs.next()) {
                     try {
                         callback.row(rowMapper.mapRow(rs, rowNum), rowNum);
                     }catch (Exception e) {
                         error.accept(e, rs);
                     }finally {
                         rowNum++;
                     }
                 }
                 return null;
             }
         };
     }
}
