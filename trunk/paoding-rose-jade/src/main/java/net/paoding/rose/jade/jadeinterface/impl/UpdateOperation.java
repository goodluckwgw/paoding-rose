package net.paoding.rose.jade.jadeinterface.impl;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import net.paoding.rose.jade.jadeinterface.Identity;
import net.paoding.rose.jade.jadeinterface.annotation.SQL;
import net.paoding.rose.jade.jadeinterface.annotation.SQLParam;
import net.paoding.rose.jade.jadeinterface.provider.DataAccess;
import net.paoding.rose.jade.jadeinterface.provider.Modifier;

import org.apache.commons.lang.ClassUtils;
import org.springframework.util.NumberUtils;

/**
 * 
 * 
 * @author 王志亮 [qieqie.wang@gmail.com]
 */
public class UpdateOperation implements JdbcOperation {

    private RowMapperFactory rowMapperFactory;

    public void setRowMapperFactory(RowMapperFactory rowMapperFactory) {
        this.rowMapperFactory = rowMapperFactory;
    }

    public RowMapperFactory getRowMapperFactory() {
        return rowMapperFactory;
    }

    @Override
    public Object execute(DataAccess dataAccess, Class<?> daoClass, Method method, Object[] args) {

        // 将参数放入  Map, 并且检查是否需要批量执行
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        Map<String, Object> parameters = new HashMap<String, Object>();

        // 批量执行的参数与集合
        SQLParam batchParam = null;
        Collection<?> collection = null;

        for (int i = 0; i < parameterAnnotations.length; i++) {

            // 检查参数的 Annotation
            Annotation[] annotations = parameterAnnotations[i];
            for (Annotation annotation : annotations) {

                // 参数必须用  @SQLParam 标注
                if (annotation instanceof SQLParam) {

                    if (args[i] instanceof Collection<?>) {

                        if (batchParam != null) {
                            throw new IllegalArgumentException(
                                    "Too many collection arguments in a batch method");
                        }

                        // 纪录批量
                        batchParam = (SQLParam) annotation;
                        collection = (Collection<?>) args[i];

                    } else {
                        // 纪录参数
                        parameters.put(((SQLParam) annotation).value(), args[i]);
                    }

                    break;
                }
            }
        }

        if (batchParam != null) {
            // 批量执行查询
            return executeBatch(dataAccess, batchParam.value(), collection, method, parameters);
        } else {
            // 单个执行查询
            return execute(dataAccess, method, parameters, method.getReturnType());
        }
    }

    private Object executeBatch(DataAccess dataAccess, String parameterName,
            Collection<?> collection, Method method, Map<String, Object> parameters) {

        Class<?> batchReturnClazz = method.getReturnType();

        Class<?> returnClazz = batchReturnClazz;

        Object returnArray = null;

        boolean successful = true;

        int updated = 0;

        // 转换基本类型
        if (batchReturnClazz.isPrimitive()) {
            batchReturnClazz = ClassUtils.primitiveToWrapper(batchReturnClazz);
        }

        if (batchReturnClazz.isArray()) {
            // 返回数组
            returnClazz = batchReturnClazz.getComponentType();
            returnArray = Array.newInstance(batchReturnClazz.getComponentType(), collection.size());
        } else if (batchReturnClazz == Boolean.class) {
            // 返回成功与否
            returnClazz = Boolean.class;
        } else if (batchReturnClazz == Integer.class || batchReturnClazz == Long.class
                || Number.class.isAssignableFrom(batchReturnClazz)) {
            // 返回更新纪录数
            returnClazz = Integer.class;
        }

        int index = 0;

        // 批量执行查询
        for (Object arg : collection) {

            // 更新执行参数
            parameters.put(parameterName, arg);

            Object value = execute(dataAccess, method, parameters, returnClazz);

            if (batchReturnClazz.isArray()) {
                Array.set(returnArray, index, value);
            } else if (returnClazz == Boolean.class) {
                successful = successful && ((Boolean) value).booleanValue();
            } else if (returnClazz == Integer.class) {
                updated += ((Number) value).intValue();
            }

            index++;
        }

        // 转换返回值
        if (batchReturnClazz.isArray()) {
            return returnArray;
        } else if (batchReturnClazz == Boolean.class) {
            return Boolean.valueOf(successful);
        } else if (Number.class.isAssignableFrom(batchReturnClazz)) {
            return NumberUtils.convertNumberToTargetClass(Integer.valueOf(updated),
                    batchReturnClazz);
        }

        return null;
    }

    private Object execute(DataAccess dataAccess, Method method, Map<String, Object> parameters,
            Class<?> returnClazz) {

        SQL sqlCommand = method.getAnnotation(SQL.class);

        if (returnClazz == Identity.class) {

            // 执行 INSERT 查询
            Number number = dataAccess.insertReturnId(sqlCommand.value(), new Modifier(method),
                    parameters);

            // 将结果转成方法的返回类型
            return new Identity(number);

        } else {

            // 执行 UPDATE / DELETE 查询
            int updated = dataAccess.update(sqlCommand.value(), new Modifier(method), parameters);

            // 转换基本类型
            if (returnClazz.isPrimitive()) {
                returnClazz = ClassUtils.primitiveToWrapper(returnClazz);
            }

            // 将结果转成方法的返回类型
            if (returnClazz == Boolean.class) {
                return Boolean.valueOf(updated > 0);
            } else if (returnClazz == Long.class) {
                return Long.valueOf(updated);
            } else if (returnClazz == Integer.class) {
                return Integer.valueOf(updated);
            } else if (Number.class.isAssignableFrom(returnClazz)) {
                return NumberUtils.convertNumberToTargetClass( // NL
                        Integer.valueOf(updated), returnClazz);
            }
        }

        return null; // 没有返回值
    }
}