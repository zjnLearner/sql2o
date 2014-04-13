package org.sql2o;

import org.sql2o.converters.Convert;
import org.sql2o.converters.Converter;
import org.sql2o.converters.ConverterException;
import org.sql2o.data.LazyTable;
import org.sql2o.data.Row;
import org.sql2o.data.Table;
import org.sql2o.data.TableResultSetIterator;
import org.sql2o.logging.LocalLoggerFactory;
import org.sql2o.logging.Logger;
import org.sql2o.quirks.Quirks;
import org.sql2o.reflection.PojoIntrospector;
import org.sql2o.tools.NamedParameterStatement;
import org.sql2o.tools.ResultSetUtils;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.sql.*;
import java.util.*;

/**
 * Represents a sql2o statement. With sql2o, all statements are instances of the Query class.
 */
@SuppressWarnings("UnusedDeclaration")
public class Query {

    private final Logger logger = LocalLoggerFactory.getLogger(Query.class);

    private Connection connection;
    private Map<String, String> caseSensitiveColumnMappings;
    private Map<String, String> columnMappings;
    private NamedParameterStatement statement;
    private boolean caseSensitive;
    private boolean autoDeriveColumnNames;
    private String name;
    private boolean returnGeneratedKeys;

    public Query(Connection connection, String queryText, String name, boolean returnGeneratedKeys) {
        this.connection = connection;
        this.name = name;
        this.returnGeneratedKeys = returnGeneratedKeys;

        try{
            statement = new NamedParameterStatement(connection.getJdbcConnection(), queryText, returnGeneratedKeys);
        }
        catch(Exception ex){
            throw new RuntimeException(ex);
        }

        this.setColumnMappings(connection.getSql2o().getDefaultColumnMappings());
        this.caseSensitive = connection.getSql2o().isDefaultCaseSensitive();
    }

    // ------------------------------------------------
    // ------------- Getter/Setters -------------------
    // ------------------------------------------------

    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    public Query setCaseSensitive(boolean caseSensitive) {
        this.caseSensitive = caseSensitive;
        return this;
    }

    public boolean isAutoDeriveColumnNames() {
        return autoDeriveColumnNames;
    }

    public Query setAutoDeriveColumnNames(boolean autoDeriveColumnNames) {
        this.autoDeriveColumnNames = autoDeriveColumnNames;
        return this;
    }

    public Connection getConnection(){
        return this.connection;
    }

    public String getName() {
        return name;
    }

    private ResultSetHandlerFactoryBuilder resultSetHandlerFactoryBuilder;

    // ------------------------------------------------
    // ------------- Add Parameters -------------------
    // ------------------------------------------------

    public Query addParameter(String name, Object value) {
        value = convertParameter(value);

        try {
            statement.setObject(name, value);
        }
        catch(SQLException ex) {
            throw new RuntimeException(ex);
        }
        return this;
    }

    private Object convertParameter(Object value) {
        if (value == null) {
            return null;
        }
        Converter converter = Convert.getConverterIfExists(value.getClass());
        if (converter == null) {
            return null;
        }
        //noinspection unchecked
        return converter.toDatabaseParam(value);
    }

    public Query addParameter(String name, InputStream value){
        try{
            statement.setInputStream(name, value);
        }
        catch(SQLException ex){
            throw new RuntimeException(ex);
        }
        return this;
    }

    public Query addParameter(String name, int value){
        try {
            statement.setInt(name, value);
        }
        catch(SQLException ex) {
            throw new RuntimeException(ex);
        }
        return this;
    }

    public Query addParameter(String name, Integer value) {
        try {
            if (value == null) {
                statement.setNull(name, Types.INTEGER);
            }
            else {
                statement.setInt(name, value);
            }
        }
        catch(SQLException ex){
            throw new RuntimeException(ex);
        }
        return this;
    }

    public Query addParameter(String name, long value){
        try {
            statement.setLong(name, value);
        }
        catch(SQLException ex) {
            throw new RuntimeException(ex);
        }
        return this;
    }

    public Query addParameter(String name, Long value){
        try {
            if (value == null) {
                statement.setNull(name, Types.BIGINT);
            }
            else {
                statement.setLong(name, value);
            }
        }
        catch (SQLException ex){
            throw new Sql2oException(ex);
        }
        return this;
    }

    public Query addParameter(String name, String value) {
        try {
            if (value == null) {
                statement.setNull(name, Types.VARCHAR);
            }
            else {
                statement.setString(name, value);
            }
        }
        catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
        return this;
    }

    public Query addParameter(String name, Timestamp value){
        try {
            if (value == null) {
                statement.setNull(name, Types.TIMESTAMP);
            }
            else {
                statement.setTimestamp(name, value);
            }
        }
        catch(SQLException ex) {
            throw new RuntimeException(ex);
        }
        return this;
    }

    public Query addParameter(String name, Time value) {
        try {
            if (value == null){
                statement.setNull(name, Types.TIME);
            }
            else {
                statement.setTime(name,value);
            }
        }
        catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return this;
    }

    public Query bind(Object pojo) {
        Class clazz = pojo.getClass();
        Map<String, PojoIntrospector.ReadableProperty> propertyMap = PojoIntrospector.readableProperties(clazz);
        for (PojoIntrospector.ReadableProperty property : propertyMap.values()) {
            try {
                if(statement.containsParameter(property.name))
                    this.addParameter(property.name, property.get(pojo));
            }
            catch(IllegalArgumentException ex) {
                logger.debug("Ignoring Illegal Arguments", ex);
            }
            catch(IllegalAccessException ex) {
                throw new RuntimeException(ex);
            }
            catch (InvocationTargetException ex) {
                throw new RuntimeException(ex);
            }
        }
        return this;
    }

    public ResultSetHandlerFactoryBuilder getResultSetHandlerFactoryBuilder() {
        return resultSetHandlerFactoryBuilder;
    }

    public void setResultSetHandlerFactoryBuilder(ResultSetHandlerFactoryBuilder resultSetHandlerFactoryBuilder) {
        this.resultSetHandlerFactoryBuilder = resultSetHandlerFactoryBuilder;
    }

    // ------------------------------------------------
    // -------------------- Execute -------------------
    // ------------------------------------------------

    /**
     * Iterable {@link java.sql.ResultSet} that wraps {@link PojoResultSetIterator}.
     */
    private abstract class ResultSetIterableBase<T> implements ResultSetIterable<T> {
        private long start;
        private long afterExecQuery;
        protected ResultSet rs;

        public ResultSetIterableBase() {
            try {
                start = System.currentTimeMillis();
                rs = statement.executeQuery();
                afterExecQuery = System.currentTimeMillis();
            }
            catch (SQLException ex) {
                throw new Sql2oException("Database error: " + ex.getMessage(), ex);
            }
        }

        public void close() {
            try {
                if (rs != null) {
                    rs.close();

                    // log the query
                    long afterClose = System.currentTimeMillis();
                    logger.debug("total: {} ms, execution: {} ms, reading and parsing: {} ms; executed [{}]", new Object[]{
                            afterClose - start,
                            afterExecQuery-start,
                            afterClose - afterExecQuery,
                            name
                    });

                    rs = null;
                }
            }
            catch (SQLException ex) {
                throw new Sql2oException("Error closing ResultSet.", ex);
            }
            finally {
                closeConnectionIfNecessary();
            }
        }
    }

    /**
     * Read a collection lazily. Generally speaking, this should only be used if you are reading MANY
     * results and keeping them all in a Collection would cause memory issues. You MUST call
     * {@link org.sql2o.ResultSetIterable#close()} when you are done iterating.
     *
     * @param returnType type of each row
     * @return iterable results
     */
    public <T> ResultSetIterable<T> executeAndFetchLazy(final Class<T> returnType) {
        final ResultSetHandlerFactory<T> resultSetHandlerFactory = newResultSetHandlerFactory(returnType);
        return executeAndFetchLazy(resultSetHandlerFactory);
    }

    private <T> ResultSetHandlerFactory<T> newResultSetHandlerFactory(Class<T> returnType) {
        final Quirks quirks = getConnection().getSql2o().getQuirks();
        ResultSetHandlerFactoryBuilder builder = getResultSetHandlerFactoryBuilder();
        if(builder==null) builder=new DefaultResultSetHandlerFactoryBuilder();
        builder.setAutoDeriveColumnNames(autoDeriveColumnNames);
        builder.setCaseSensitive(caseSensitive);
        builder.setColumnMappings(columnMappings);
        builder.setQuirks(quirks);
        return builder.newFactory(returnType);
    }

    /**
     * Read a collection lazily. Generally speaking, this should only be used if you are reading MANY
     * results and keeping them all in a Collection would cause memory issues. You MUST call
     * {@link org.sql2o.ResultSetIterable#close()} when you are done iterating.
     *
     * @param resultSetHandlerFactory factory to provide ResultSetHandler
     * @return iterable results
     */
    public <T> ResultSetIterable<T> executeAndFetchLazy(final ResultSetHandlerFactory<T> resultSetHandlerFactory) {
        final Quirks quirks = getConnection().getSql2o().getQuirks();
        return new ResultSetIterableBase<T>() {
            public Iterator<T> iterator() {
                return new PojoResultSetIterator<T>(rs, isCaseSensitive(), quirks, resultSetHandlerFactory);
            }
        };
    }

    /**
     * Read a collection lazily. Generally speaking, this should only be used if you are reading MANY
     * results and keeping them all in a Collection would cause memory issues. You MUST call
     * {@link org.sql2o.ResultSetIterable#close()} when you are done iterating.
     *
     * @param resultSetHandler ResultSetHandler
     * @return iterable results
     */
    public <T> ResultSetIterable<T> executeAndFetchLazy(final ResultSetHandler<T> resultSetHandler) {
        final ResultSetHandlerFactory<T> factory = newResultSetHandlerFactory(resultSetHandler);
        return executeAndFetchLazy(factory);
    }

    private static  <T> ResultSetHandlerFactory<T> newResultSetHandlerFactory(final ResultSetHandler<T> resultSetHandler) {
        return new ResultSetHandlerFactory<T>() {
            public ResultSetHandler<T> newResultSetHandler(ResultSetMetaData resultSetMetaData) throws SQLException {
                return resultSetHandler;
            }
        };
    }

    public <T> List<T> executeAndFetch(Class<T> returnType){
        return executeAndFetch(newResultSetHandlerFactory(returnType));
    }

    public <T> List<T> executeAndFetch(ResultSetHandler<T> resultSetHandler){
        return executeAndFetch(newResultSetHandlerFactory(resultSetHandler));
    }

    public <T> List<T> executeAndFetch(ResultSetHandlerFactory<T> factory){
        List<T> list = new ArrayList<T>();

        // if sql2o moves to java 7 at some point, this could be much cleaner using try-with-resources
        ResultSetIterable<T> iterable = null;
        try {
            iterable = executeAndFetchLazy(factory);
            for (T item : iterable) {
                list.add(item);
            }
        }
        finally {
            if (iterable != null) {
                iterable.close();
            }
        }

        return list;
    }

    public <T> T executeAndFetchFirst(Class<T> returnType){
        return executeAndFetchFirst(newResultSetHandlerFactory(returnType));
    }

    public <T> T executeAndFetchFirst(ResultSetHandler<T> resultSetHandler){
        return executeAndFetchFirst(newResultSetHandlerFactory(resultSetHandler));
    }

    public <T> T executeAndFetchFirst(ResultSetHandlerFactory<T> resultSetHandlerFactory){
        // if sql2o moves to java 7 at some point, this could be much cleaner using try-with-resources
        ResultSetIterable<T> iterable = null;
        try {
            iterable = executeAndFetchLazy(resultSetHandlerFactory);
            Iterator<T> iterator = iterable.iterator();
            return iterator.hasNext() ? iterator.next() : null;
        }
        finally {
            if (iterable != null) {
                iterable.close();
            }
        }
    }

    public LazyTable executeAndFetchTableLazy() {
        final LazyTable lt = new LazyTable();

        lt.setRows(new ResultSetIterableBase<Row>() {
            public Iterator<Row> iterator() {
                return new TableResultSetIterator(rs, isCaseSensitive(), getConnection().getSql2o().getQuirks(), lt);
            }
        });

        return lt;
    }

    public Table executeAndFetchTable() {
        LazyTable lt = null;

        List<Row> rows = new ArrayList<Row>();

        try {
            lt = executeAndFetchTableLazy();

            for (Row item : lt.rows()) {
                rows.add(item);
            }
        }
        finally {
            if (lt != null) {
                lt.close();
            }
        }

        return lt == null ? null : new Table(lt.getName(), rows, lt.columns());
    }

    public Connection executeUpdate(){
        long start = System.currentTimeMillis();
        try{

            this.connection.setResult(statement.executeUpdate());
            this.connection.setKeys(this.returnGeneratedKeys ? statement.getStatement().getGeneratedKeys() : null);
            connection.setCanGetKeys(this.returnGeneratedKeys);
        }
        catch(SQLException ex){
            this.connection.onException();
            throw new Sql2oException("Error in executeUpdate, " + ex.getMessage(), ex);
        }
        finally {
            closeConnectionIfNecessary();
        }

        long end = System.currentTimeMillis();
        logger.debug("total: {} ms; executed update [{}]", new Object[]{
                end - start,
                this.getName() == null ? "No name" : this.getName()
        });

        return this.connection;
    }

    public Object executeScalar(){
        long start = System.currentTimeMillis();
        try {
            ResultSet rs = this.statement.executeQuery();
            if (rs.next()){
                Object o = this.connection.getSql2o().getQuirks().getRSVal(rs, 1);
                long end = System.currentTimeMillis();
                logger.debug("total: {} ms; executed scalar [{}]", new Object[]{
                        end - start,
                        this.getName() == null ? "No name" : this.getName()
                });
                return o;
            }
            else{
                return null;
            }

        }
        catch (SQLException e) {
            this.connection.onException();
            throw new Sql2oException("Database error occurred while running executeScalar: " + e.getMessage(), e);
        }
        finally{
            closeConnectionIfNecessary();
        }

    }

    public <V> V executeScalar(Class<V> returnType){
        try {
            Converter<V> converter;
            //noinspection unchecked
            converter = Convert.getConverter(returnType);
            //noinspection unchecked
            return executeScalar(converter);
        } catch (ConverterException e) {
            throw new Sql2oException("Error occured while converting value from database to type " + returnType, e);
        }
    }

    public <V> V executeScalar(Converter<V> converter){
        try {
            //noinspection unchecked
            return converter.convert(executeScalar());
        } catch (ConverterException e) {
            throw new Sql2oException("Error occured while converting value from database", e);
        }
    }



    public <T> List<T> executeScalarList(final Class<T> returnType){
        return executeAndFetch(newScalarResultSetHandler(returnType));
    }

    @SuppressWarnings("unchecked")
    private <T> ResultSetHandler<T> newScalarResultSetHandler(final Class<T> returnType) {
        final Quirks quirks = this.connection.getSql2o().getQuirks();
        try {
            final Converter<T> converter = Convert.getConverter(returnType);
            return new ResultSetHandler<T>() {
                public T handle(ResultSet resultSet) throws SQLException {
                    Object value = quirks.getRSVal(resultSet, 1);
                    try {
                        return (converter.convert(value));
                    } catch (ConverterException e) {
                        throw new Sql2oException("Error occurred while converting value from database to type " + returnType, e);
                    }
                }
            };
        } catch (ConverterException e) {
            throw new Sql2oException("Can't get converter for type " + returnType, e);
        }
    }

    /************** batch stuff *******************/

    public Query addToBatch(){
        try {
            statement.addBatch();
        } catch (SQLException e) {
            throw new Sql2oException("Error while adding statement to batch", e);
        }

        return this;
    }

    public Connection executeBatch() throws Sql2oException {
        long start = System.currentTimeMillis();
        try {
            connection.setBatchResult(statement.executeBatch());
            connection.setKeys(this.returnGeneratedKeys ? statement.getStatement().getGeneratedKeys() : null);
            connection.setCanGetKeys(this.returnGeneratedKeys);
        }
        catch (Throwable e) {
            this.connection.onException();
            throw new Sql2oException("Error while executing batch operation: " + e.getMessage(), e);
        }
        finally {
            closeConnectionIfNecessary();
        }

        long end = System.currentTimeMillis();
        logger.debug("total: {} ms; executed batch [{}]", new Object[]{
                end - start,
                this.getName() == null ? "No name" : this.getName()
        });

        return this.connection;
    }

    /*********** column mapping ****************/

    public Map<String, String> getColumnMappings() {
        if (this.isCaseSensitive()){
            return this.caseSensitiveColumnMappings;
        }
        else{
            return this.columnMappings;
        }
    }

    public Query setColumnMappings(Map<String, String> mappings){

        this.caseSensitiveColumnMappings = new HashMap<String, String>();
        this.columnMappings = new HashMap<String, String>();

        for (Map.Entry<String,String> entry : mappings.entrySet()){
            this.caseSensitiveColumnMappings.put(entry.getKey(), entry.getValue());
            this.columnMappings.put(entry.getKey().toLowerCase(), entry.getValue().toLowerCase());
        }

        return this;
    }

    public Query addColumnMapping(String columnName, String propertyName){
        this.caseSensitiveColumnMappings.put(columnName, propertyName);
        this.columnMappings.put(columnName.toLowerCase(), propertyName.toLowerCase());

        return this;
    }

    /************** private stuff ***************/

    private void closeConnectionIfNecessary(){
        try{
            if (connection.autoClose && !connection.getJdbcConnection().isClosed() && statement != null){
                this.statement.close();
                this.connection.getJdbcConnection().close();
            }
        }
        catch (Exception ex){
            throw new RuntimeException("Error while attempting to close connection", ex);
        }
    }
}
