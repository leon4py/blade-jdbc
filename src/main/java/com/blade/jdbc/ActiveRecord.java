package com.blade.jdbc;

import com.blade.jdbc.annotation.Table;
import com.blade.jdbc.annotation.Transient;
import lombok.Setter;
import org.sql2o.Connection;
import org.sql2o.Sql2o;

import java.io.Serializable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ActiveRecord implements Serializable {

    @Transient
    @Setter
    protected Sql2o sql2o;

    @Transient
    private Set<WhereParam> whereValues = new LinkedHashSet<>();

    @Transient
    private Set<String> saveOrUpdateProperties = new TreeSet<>();

    public ActiveRecord() {
    }

    public <T extends ActiveRecord> T where(String key, Object value) {
        return this.where(key, "=", value);
    }

    public <T extends ActiveRecord> T where(String key, String opt, Object value) {
        this.whereValues.add(WhereParam.builder().key(key).opt(opt).value(value).build());
        this.saveOrUpdateProperties.add(key);
        return (T) this;
    }

    public void save() {
        String tableName = getTableName();

        StringBuilder sb = new StringBuilder("insert into ");
        sb.append(tableName);
        sb.append(" (");

        StringBuffer values = new StringBuffer(" values (");
        Stream.of(getClass().getDeclaredFields())
                .filter(field -> null == field.getAnnotation(Transient.class))
                .forEach(field -> {
                    sb.append(field.getName()).append(", ");
                    values.append(':').append(field.getName()).append(", ");
                });

        sb.append(')');
        values.append(')');

        String sql = sb.append(values).toString().replace(", )", ")");
        try (Connection con = getConn()) {
            con.createQuery(sql).bind(this).executeUpdate();
        }
    }

    public void update(Serializable pk) {
        this.update(getPk(), pk);
    }

    public void update(String field, Object value) {
        this.whereValues.add(WhereParam.builder().key(field).opt("=").value(value).build());
        this.update();
    }

    public void update() {
        String sql;
        String tableName = getTableName();
        StringBuilder sb = new StringBuilder("update ");
        sb.append(tableName);
        sb.append(" set ");

        final int[] pos = {1};
        List<Object> list = this.parseSet(pos, sb);

        sb.append("where ");
        whereValues.forEach(where -> {
            sb.append(where.getKey()).append(" " + where.getOpt() + " ").append(":p").append(pos[0]++).append(" and ");
            list.add(where.getValue());
        });

        sql = sb.toString().replace(", where ", " where ");
        if (sql.endsWith("and ")) {
            sql = sql.substring(0, sql.length() - 5);
        }

        Object[] args = list.toArray();
        this.execute(sql, args);
        this.cleanParam();
    }

    private void execute(String sql, Object[] args) {
        if (null == connectionThreadLocal) {
            try (Connection con = sql2o.open()) {
                con.createQuery(sql).withParams(args).executeUpdate();
            }
        } else {
            Connection con = getConn();
            con.createQuery(sql).withParams(args).executeUpdate();
        }
    }

    private Connection getConn() {
        return null != connectionThreadLocal.get() ? connectionThreadLocal.get() : sql2o.open();
    }

    public <T extends ActiveRecord> T query(String sql, Object... args) {
        Class<T> type = (Class<T>) getClass();
        try (Connection con = sql2o.open()) {
            this.cleanParam();
            return con.createQuery(sql).withParams(args)
                    .executeAndFetchFirst(type);
        }
    }

    public <T extends ActiveRecord> List<T> queryAll(String sql, Object... args) {
        Class<T> type = (Class<T>) getClass();
        try (Connection con = sql2o.open()) {
            this.cleanParam();
            return con.createQuery(sql).withParams(args)
                    .executeAndFetch(type);
        }
    }

    public <T extends ActiveRecord> List<T> findAll() {
        return this.findAll(null);
    }

    public <T extends ActiveRecord> List<T> findAll(Supplier<ConditionEnum>... conditions) {

        String initSql = this.parseFieldsSql(conditions);

        StringBuilder sqlBuf = new StringBuilder(initSql);

        int[] pos = {1};
        List<Object> list = this.parseWhere(pos, sqlBuf);
        List<Object> temp = andWhere(pos, sqlBuf);
        if (null != temp) {
            list.addAll(temp);
        }

        String sql = sqlBuf.toString();

        sql = sql.replace(", where", " where");
        if (sql.endsWith(" and ")) {
            sql = sql.substring(0, sql.length() - 5);
        }
        if (sql.endsWith(", ")) {
            sql = sql.substring(0, sql.length() - 2);
        }

        String orderBy = this.parseOrderBySql(conditions);
        if (null != orderBy) {
            sql += orderBy;
        }

        String limit = this.parseLimitBySql(conditions);
        if (null != limit) {
            sql += orderBy;
        }

        Object[] args = list.toArray();

        Class<T> type = (Class<T>) getClass();
        try (Connection con = sql2o.open()) {
            this.cleanParam();
            return con.createQuery(sql).withParams(args)
                    .executeAndFetch(type);
        }
    }

    private String parseLimitBySql(Supplier<ConditionEnum>[] conditions) {
        final String[] sql = {null};
        if (null == conditions) {
            return sql[0];
        }
        Stream.of(conditions)
                .filter(conditionEnumSupplier -> conditionEnumSupplier.get().equals(ConditionEnum.LIMIT))
                .findFirst()
                .ifPresent(conditionEnumSupplier -> {
                    Limit limit = (Limit) conditionEnumSupplier;
                    sql[0] = " limit " + limit.getPage() + ", " + limit.getLimit();
                });
        return sql[0];
    }

    private String parseOrderBySql(Supplier<ConditionEnum>[] conditions) {
        final String[] sql = {null};
        if (null == conditions) {
            return sql[0];
        }
        Stream.of(conditions)
                .filter(conditionEnumSupplier -> conditionEnumSupplier.get().equals(ConditionEnum.ORDER_BY))
                .findFirst()
                .ifPresent(conditionEnumSupplier -> {
                    OrderBy orderBy = (OrderBy) conditionEnumSupplier;
                    sql[0] = " order by " + orderBy.getOrderBy();
                });
        return sql[0];
    }

    private String parseFieldsSql(Supplier<ConditionEnum>[] conditions) {
        final String[] sql = {"select * from " + getTableName()};
        if (null == conditions) {
            return sql[0];
        }
        Stream.of(conditions)
                .filter(conditionEnumSupplier -> conditionEnumSupplier.get().equals(ConditionEnum.FIELDS))
                .findFirst()
                .ifPresent(conditionEnumSupplier -> {
                    Fields fields = (Fields) conditionEnumSupplier;
                    Set<String> fieldsSet = fields.getFields();
                    sql[0] = "select " + fieldsSet.stream().collect(Collectors.joining(",")) + " from " + getTableName();
                });
        return sql[0];
    }

    public <T extends ActiveRecord> T find(Serializable id) {
        String sql = "select * from " + getTableName() + " where " + getPk() + " = :p1";
        Class<T> type = (Class<T>) getClass();
        try (Connection con = sql2o.open()) {
            this.cleanParam();
            return con.createQuery(sql)
                    .withParams(id)
                    .executeAndFetchFirst(type);
        }
    }

    public long count() {
        StringBuilder sqlBuf = new StringBuilder("select count(*) from " + getTableName());

        int[] pos = {1};
        List<Object> list = this.parseWhere(pos, sqlBuf);

        List<Object> temp = andWhere(pos, sqlBuf);
        if (null != temp) {
            list.addAll(temp);
        }

        String sql = sqlBuf.toString();

        sql = sql.replace(", where", " where");
        if (sql.endsWith(" and ")) {
            sql = sql.substring(0, sql.length() - 5);
        }
        if (sql.endsWith(", ")) {
            sql = sql.substring(0, sql.length() - 2);
        }

        Object[] args = list.toArray();

        try (Connection con = sql2o.open()) {
            this.cleanParam();
            return con.createQuery(sql).withParams(args)
                    .executeAndFetchFirst(Long.class);
        }
    }

    private String getTableName() {
        Class<?> modelType = getClass();
        Table table = modelType.getAnnotation(Table.class);
        if (null != table) {
            return table.value().toLowerCase();
        }
        return modelType.getSimpleName().toLowerCase();
    }

    private String getPk() {
        Class<?> modelType = getClass();
        Table table = modelType.getAnnotation(Table.class);
        if (null != table) {
            return table.pk().toLowerCase();
        }
        return "id";
    }

    public void delete() {
        StringBuilder sqlBuf = new StringBuilder("delete from " + getTableName());

        int[] pos = {1};
        List<Object> list = this.parseWhere(pos, sqlBuf);

        if (whereValues.isEmpty()) {
            throw new RuntimeException("Delete operation must take conditions.");
        } else {
            if (sqlBuf.indexOf(" where ") == -1) {
                sqlBuf.append(" where ");
            }
        }

        List<Object> temp = andWhere(pos, sqlBuf);
        if (null != temp) {
            list.addAll(temp);
        }

        String sql = sqlBuf.toString();

        sql = sql.replace(", where", " where");
        if (sql.endsWith("and ")) {
            sql = sql.substring(0, sql.length() - 5);
        }

        Object[] args = list.toArray();

        this.execute(sql, args);
        this.cleanParam();
    }

    public void delete(Serializable pk) {
        this.delete(getPk(), pk);
    }

    public void delete(String field, Object value) {
        whereValues.add(WhereParam.builder().key(field).opt("=").value(value).build());
        this.delete();
    }

    private static final ThreadLocal<Connection> connectionThreadLocal = new ThreadLocal<>();

    public void atomic(Tx tx) {
        try {
            connectionThreadLocal.set(sql2o.beginTransaction());
            try (Connection con = connectionThreadLocal.get()) {
                tx.run();
                con.commit();
            }
        } catch (RuntimeException e) {
            connectionThreadLocal.get().rollback();
            throw e;
        } finally {
            connectionThreadLocal.remove();
        }
    }

    private void cleanParam() {
        this.whereValues.clear();
        this.saveOrUpdateProperties.clear();
        Stream.of(getClass().getDeclaredFields())
                .filter(field -> null == field.getAnnotation(Transient.class))
                .forEach(field -> Unchecked.wrap(() -> {
                    field.setAccessible(true);
                    field.set(this, null);
                    return null;
                }));
    }

    private List<Object> parseSet(int[] pos, StringBuilder sqlBuf) {
        return Stream.of(getClass().getDeclaredFields())
                .filter(field -> null == field.getAnnotation(Transient.class))
                .filter(field -> null != Unchecked.wrap(() -> {
                    field.setAccessible(true);
                    return field.get(this);
                }))
                .map(field -> Unchecked.wrap(() -> {
                    Object value = field.get(this);
                    sqlBuf.append(field.getName()).append(" = ").append(":p").append(pos[0]++).append(", ");
                    return value;
                }))
                .collect(Collectors.toList());
    }

    private List<Object> parseWhere(int[] pos, StringBuilder sqlBuf) {
        return Stream.of(getClass().getDeclaredFields())
                .filter(field -> null == field.getAnnotation(Transient.class))
                .filter(field -> null != Unchecked.wrap(() -> {
                    field.setAccessible(true);
                    return field.get(this);
                }))
                .map(field -> Unchecked.wrap(() -> {
                    if (sqlBuf.indexOf(" where ") == -1) {
                        sqlBuf.append(" where ");
                    }
                    Object value = field.get(this);
                    sqlBuf.append(field.getName()).append(" = ").append(":p").append(pos[0]++).append(" and ");
                    return value;
                }))
                .collect(Collectors.toList());
    }

    private List<Object> andWhere(int[] pos, StringBuilder sqlBuf) {
        if (!whereValues.isEmpty()) {
            if (sqlBuf.indexOf(" where ") == -1) {
                sqlBuf.append(" where ");
            }
            return whereValues.stream()
                    .map(where -> {
                        sqlBuf.append(where.getKey()).append(" " + where.getOpt() + " ").append(":p").append(pos[0]++).append(" and ");
                        return where.getValue();
                    })
                    .collect(Collectors.toList());
        }
        return null;
    }
}
