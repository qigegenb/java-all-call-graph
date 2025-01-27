package com.adrninistrator.jacg.runner.base;

import com.adrninistrator.jacg.annotation.AnnotationStorage;
import com.adrninistrator.jacg.common.DC;
import com.adrninistrator.jacg.common.JACGConstants;
import com.adrninistrator.jacg.common.enums.ConfigKeyEnum;
import com.adrninistrator.jacg.common.enums.OtherConfigFileUseSetEnum;
import com.adrninistrator.jacg.dto.annotation.MethodWithAnnotationInfo;
import com.adrninistrator.jacg.dto.annotation_attribute.BaseAnnotationAttribute;
import com.adrninistrator.jacg.dto.multiple.MultiCallInfo;
import com.adrninistrator.jacg.dto.notice.NoticeCallInfo;
import com.adrninistrator.jacg.dto.task.FindMethodInfo;
import com.adrninistrator.jacg.extensions.annotation_handler.AbstractAnnotationHandler;
import com.adrninistrator.jacg.extensions.annotation_handler.DefaultAnnotationHandler;
import com.adrninistrator.jacg.runner.RunnerGenAllGraph4Callee;
import com.adrninistrator.jacg.util.JACGFileUtil;
import com.adrninistrator.jacg.util.JACGSqlUtil;
import com.adrninistrator.jacg.util.JACGUtil;
import com.adrninistrator.javacg.enums.CallTypeEnum;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author adrninistrator
 * @date 2021/6/18
 * @description:
 */

public abstract class AbstractRunnerGenCallGraph extends AbstractRunner {

    private static final Logger logger = LoggerFactory.getLogger(AbstractRunnerGenCallGraph.class);

    // 配置文件中指定的需要处理的任务
    protected Set<String> taskSet;

    /*
        key: 配置文件中指定的类名
        value: 对应的简单类名或完整类名
     */
    protected Map<String, String> simpleClassNameMap = new HashMap<>();

    // 保存当前生成输出文件时的目录前缀
    protected String outputDirPrefix;

    /*
        接口调用对应实现类的方法调用
        key: 接口方法
        value: 实现类方法
     */
    private final Map<String, MultiCallInfo> itfMethodCallMap = new HashMap<>();

    /*
        抽象父类调用对应子类的方法调用
        key: 抽象父类方法
        value: 子类方法
     */
    private final Map<String, MultiCallInfo> sccMethodCallMap = new HashMap<>();

    // 接口调用对应实现类的方法调用，存在一对多的接口
    private final Set<String> itfMultiCallerFullMethodSet = new TreeSet<>();

    // 抽象父类调用对应子类的方法调用，存在一对多的抽象父类
    private final Set<String> sccMultiCallerFullMethodSet = new TreeSet<>();

    /*
        被禁用的接口调用对应实现类的方法调用
        key: 接口方法
        value: 实现类方法
     */
    private final Map<String, MultiCallInfo> disabledItfMethodCallMap = new TreeMap<>();

    /*
        被禁用的抽象父类调用对应子类的方法调用
        key: 抽象父类方法
        value: 子类方法
     */
    private final Map<String, MultiCallInfo> disabledSccMethodCallMap = new TreeMap<>();

    // 保存用于对方法上的注解进行处理的类
    protected List<AbstractAnnotationHandler> annotationHandlerList;

    // 保存各个方法已处理过的所有注解信息
    protected Map<String, String> methodAllAnnotationInfoMap = new HashMap<>();

    /*
        保存配置文件中指定的方法信息与生成文件名之间的映射关系
        key: 配置文件中指定的方法信息
        value: 生成文件名
     */
    protected Map<String, String> methodInConfAndFileMap = new ConcurrentHashMap<>();

    // 保存已生成的过方法文件名
    protected Map<String, Boolean> writtenFileNameMap = new ConcurrentHashMap<>();

    // 保存类及方法上的注解信息
    protected AnnotationStorage annotationStorage;

    // 设置输出文件根目录
    public static void setOutputRootPath(String outputRootPath) {
        System.setProperty(JACGConstants.PROPERTY_OUTPUT_ROOT_PATH, outputRootPath);
    }

    // 初始化保存类及方法上的注解信息
    protected boolean initAnnotationStorage() {
        if (!confInfo.isShowMethodAnnotation()) {
            return true;
        }
        annotationStorage = new AnnotationStorage(dbOperator, confInfo.getAppName());
        return annotationStorage.init();
    }

    /**
     * 获取简单类名或完整类名
     *
     * @param className
     * @return null: 未获取到，非null: 若不存在同名类，则返回简单类名；若存在同名类，则返回完整类名
     */
    protected String getSimpleClassName(String className) {
        String simpleClassName = simpleClassNameMap.get(className);
        if (simpleClassName != null) {
            return simpleClassName;
        }

        // 执行获取简单类名或完整类名
        simpleClassName = doGetSimpleClassName(className);
        if (simpleClassName == null) {
            return null;
        }

        simpleClassNameMap.put(className, simpleClassName);
        return simpleClassName;
    }

    // 执行获取简单类名或完整类名
    protected String doGetSimpleClassName(String className) {
        if (className.contains(JACGConstants.FLAG_DOT)) {
            // 当前指定的是完整类名，查找对应的简单类名
            String sqlKey = JACGConstants.SQL_KEY_CN_QUERY_SIMPLE_CLASS;
            String sql = dbOperWrapper.getCachedSql(sqlKey);
            if (sql == null) {
                sql = "select " + DC.CN_SIMPLE_NAME + " from " + JACGConstants.TABLE_PREFIX_CLASS_NAME + confInfo.getAppName() +
                        " where " + DC.CN_FULL_NAME + " = ?";
                dbOperWrapper.cacheSql(sqlKey, sql);
            }

            List<Object> list = dbOperator.queryListOneColumn(sql, new Object[]{className});
            if (JACGUtil.isCollectionEmpty(list)) {
                logger.error("指定的完整类名 {} 不存在，请检查，可能因为指定的类所在的jar包未在配置文件 {} 参数 {} 中指定",
                        className, JACGConstants.FILE_CONFIG, ConfigKeyEnum.CKE_CALL_GRAPH_JAR_LIST);
                return null;
            }
            return (String) list.get(0);
        }

        // 当前指定的是简单类名
        String sqlKey = JACGConstants.SQL_KEY_CN_QUERY_FULL_CLASS;
        String sql = dbOperWrapper.getCachedSql(sqlKey);
        if (sql == null) {
            sql = "select " + DC.CN_SIMPLE_NAME + " from " + JACGConstants.TABLE_PREFIX_CLASS_NAME + confInfo.getAppName() +
                    " where " + DC.CN_SIMPLE_NAME + " = ?";
            dbOperWrapper.cacheSql(sqlKey, sql);
        }

        List<Object> list = dbOperator.queryListOneColumn(sql, new Object[]{className});
        if (JACGUtil.isCollectionEmpty(list)) {
            logger.error("指定的简单类名 {} 不存在，请检查，可能因为以下原因\n" +
                            "1. 指定的类所在的jar包未在配置文件 {} 参数 {} 中指定\n" +
                            "2. 指定的类存在同名类，需要使用完整类名形式",
                    className, JACGConstants.FILE_CONFIG, ConfigKeyEnum.CKE_CALL_GRAPH_JAR_LIST);
            return null;
        }
        return (String) list.get(0);
    }

    // 读取配置文件中指定的需要处理的任务
    protected boolean readTaskInfo(OtherConfigFileUseSetEnum otherConfigFileUseSetEnum) {
        taskSet = configureWrapper.getOtherConfigSet(otherConfigFileUseSetEnum);
        if (JACGUtil.isCollectionEmpty(taskSet)) {
            logger.error("读取文件不存在或内容为空 {}", otherConfigFileUseSetEnum.getFileName());
            return false;
        }

        return true;
    }

    /**
     * 创建输出文件所在目录
     * 需要进行同步控制，避免创建同名目录
     *
     * @param prefix
     * @return
     */
    protected boolean createOutputDir(String prefix) {
        synchronized (AbstractRunnerGenCallGraph.class) {
            String tmpOutputDirPrefix;
            String outputRootPathInJvmOptions = JACGUtil.getDirPathInJvmOptions(JACGConstants.PROPERTY_OUTPUT_ROOT_PATH);
            if (StringUtils.isNotBlank(outputRootPathInJvmOptions)) {
                // 使用指定的生成结果文件根目录，并指定当前应用名称
                tmpOutputDirPrefix = outputRootPathInJvmOptions + prefix + File.separator + confInfo.getAppName() + JACGConstants.FLAG_MINUS + JACGUtil.currentTime();
            } else {
                // 使用当前目录作为生成结果文件根目录
                tmpOutputDirPrefix = prefix + File.separator + JACGUtil.currentTime();
            }

            outputDirPrefix = new File(tmpOutputDirPrefix).getAbsolutePath();

            logger.info("创建保存输出文件的目录 {}", outputDirPrefix);
            // 判断目录是否存在，不存在时尝试创建
            return JACGFileUtil.isDirectoryExists(outputDirPrefix);
        }
    }

    // 生成输出文件前缀，包含了当前方法的调用层级
    protected String genOutputPrefix(int level) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(JACGConstants.FLAG_LEFT_PARENTHESES).append(level).append(JACGConstants.FLAG_RIGHT_PARENTHESES)
                .append(JACGConstants.FLAG_HASHTAG)
                .append(JACGUtil.genOutputFlag(level));
        return stringBuilder.toString();
    }

    // 将输出文件合并
    protected void combineOutputFile(String fileName) {
        if (confInfo.isGenCombinedOutput()) {
            List<File> outputFileList = JACGFileUtil.findFileInCurrentDir(outputDirPrefix, JACGConstants.EXT_TXT);
            if (!JACGUtil.isCollectionEmpty(outputFileList) && outputFileList.size() > 1) {
                String combinedOutputFilePath = outputDirPrefix + File.separator + JACGConstants.COMBINE_FILE_NAME_PREFIX + fileName + JACGConstants.EXT_TXT;
                JACGFileUtil.combineTextFile(combinedOutputFilePath, outputFileList);
            }
        }
    }

    // 根据调用关系ID获取用于提示的信息
    private NoticeCallInfo queryNoticeCallInfo(int currentMethodCallId) {
        String sqlKey = JACGConstants.SQL_KEY_MC_QUERY_NOTICE_INFO;
        String sql = dbOperWrapper.getCachedSql(sqlKey);
        if (sql == null) {
            sql = "select " + JACGSqlUtil.joinColumns(DC.MC_CALLER_METHOD_HASH, DC.MC_CALLER_FULL_METHOD, DC.MC_CALLEE_FULL_METHOD) +
                    " from " + JACGConstants.TABLE_PREFIX_METHOD_CALL + confInfo.getAppName() +
                    " where " + DC.MC_CALL_ID + " = ?";
            dbOperWrapper.cacheSql(sqlKey, sql);
        }

        Map<String, Object> map = dbOperator.queryOneRow(sql, new Object[]{currentMethodCallId});
        if (JACGUtil.isMapEmpty(map)) {
            logger.error("查询需要提示的信息失败 {}", currentMethodCallId);
            return null;
        }

        String callerMethodHash = (String) map.get(DC.MC_CALLER_METHOD_HASH);
        String callerFullMethod = (String) map.get(DC.MC_CALLER_FULL_METHOD);
        String calleeFullMethod = (String) map.get(DC.MC_CALLEE_FULL_METHOD);
        if (StringUtils.isAnyBlank(callerMethodHash, callerFullMethod, calleeFullMethod)) {
            logger.error("查询需要提示的信息存在空值 {}", currentMethodCallId);
            return null;
        }
        return new NoticeCallInfo(callerMethodHash, callerFullMethod, calleeFullMethod);
    }

    // 记录可能出现一对多的方法调用
    protected boolean recordMethodCallMayBeMulti(int currentMethodCallId, String callType) {
        CallTypeEnum callTypeEnum = CallTypeEnum.getFromType(callType);
        if (callTypeEnum != CallTypeEnum.CTE_ITF && callTypeEnum != CallTypeEnum.CTE_SCC) {
            // 对于接口调用实现类、父类调用子类之外的情况，不判断是否出现出现一对多的方法调用
            return true;
        }

        // 对于接口调用实现类、父类调用子类，判断是否出现出现一对多的方法调用
        // 根据调用关系ID获取用于提示的信息
        NoticeCallInfo noticeCallInfo = queryNoticeCallInfo(currentMethodCallId);
        if (noticeCallInfo == null) {
            return false;
        }

        Map<String, MultiCallInfo> methodCallMap;
        Set<String> multiCallerFullMethodSet;

        if (callTypeEnum == CallTypeEnum.CTE_ITF) {
            methodCallMap = itfMethodCallMap;
            multiCallerFullMethodSet = itfMultiCallerFullMethodSet;
        } else {
            methodCallMap = sccMethodCallMap;
            multiCallerFullMethodSet = sccMultiCallerFullMethodSet;
        }

        String callerMethodHash = noticeCallInfo.getCallerMethodHash();
        String callerFullMethod = noticeCallInfo.getCallerFullMethod();
        String calleeFullMethod = noticeCallInfo.getCalleeFullMethod();

        MultiCallInfo multiCallInfo = methodCallMap.get(callerFullMethod);
        if (multiCallInfo == null) {
            multiCallInfo = new MultiCallInfo(callerMethodHash, new TreeSet<>());
            methodCallMap.put(callerFullMethod, multiCallInfo);
        }

        Set<String> calleeMethodSet = multiCallInfo.getCalleeFullMethodSet();
        calleeMethodSet.add(calleeFullMethod);
        if (calleeMethodSet.size() > 1) {
            multiCallerFullMethodSet.add(callerFullMethod);
        }

        return true;
    }

    // 记录被禁用的方法调用
    protected boolean recordDisabledMethodCall(int callId, String callType) {
        CallTypeEnum callTypeEnum = CallTypeEnum.getFromType(callType);
        if (callTypeEnum != CallTypeEnum.CTE_ITF && callTypeEnum != CallTypeEnum.CTE_SCC) {
            return true;
        }

        // 根据调用关系ID获取用于提示的信息
        NoticeCallInfo noticeCallInfo = queryNoticeCallInfo(callId);
        if (noticeCallInfo == null) {
            return false;
        }

        Map<String, MultiCallInfo> methodCallMap;

        if (callTypeEnum == CallTypeEnum.CTE_ITF) {
            methodCallMap = disabledItfMethodCallMap;
        } else {
            methodCallMap = disabledSccMethodCallMap;
        }

        String callerMethodHash = noticeCallInfo.getCallerMethodHash();
        String callerFullMethod = noticeCallInfo.getCallerFullMethod();
        String calleeFullMethod = noticeCallInfo.getCalleeFullMethod();

        MultiCallInfo multiCallInfo = methodCallMap.get(callerFullMethod);
        if (multiCallInfo == null) {
            multiCallInfo = new MultiCallInfo(callerMethodHash, new TreeSet<>());
            methodCallMap.put(callerFullMethod, multiCallInfo);
        }
        Set<String> calleeMethodSet = multiCallInfo.getCalleeFullMethodSet();
        calleeMethodSet.add(calleeFullMethod);

        return true;
    }

    // 打印存在一对多的方法调用
    private void printMultiMethodCall(Map<String, MultiCallInfo> methodCallMap, Set<String> multiCallerFullMethodSet, CallTypeEnum callTypeEnum) {
        // 判断相关存在一对多的调用者方法是否有被其他方法调用，若未被调用则不显示
        List<String> multiCallerFullMethodList = new ArrayList<>(multiCallerFullMethodSet.size());
        for (String multiCallerFullMethod : multiCallerFullMethodSet) {
            String multiCallerMethodHash = JACGUtil.genHashWithLen(multiCallerFullMethod);
            if (Boolean.TRUE.equals(dbOperWrapper.checkExistsNormalMethodCallByCalleeMethodHash(multiCallerMethodHash))) {
                // 当前存在一对多的调用者方法有被其他方法调用
                multiCallerFullMethodList.add(multiCallerFullMethod);
            } else {
                logger.warn("当前存在一对多的调用者方法未被其他方法调用，不打印到文件中 {}", multiCallerFullMethod);
            }
        }

        if (multiCallerFullMethodList.isEmpty()) {
            logger.info("{} 不存在一对多的方法调用，不打印相关信息", callTypeEnum);
            return;
        }

        String filePath;
        if (CallTypeEnum.CTE_ITF == callTypeEnum) {
            filePath = outputDirPrefix + File.separator + JACGConstants.NOTICE_MULTI_ITF_MD;
        } else {
            filePath = outputDirPrefix + File.separator + JACGConstants.NOTICE_MULTI_SCC_MD;
        }

        logger.info("{} 存在一对多的方法调用，打印相关信息 {}", callTypeEnum, filePath);

        StringBuilder stringBuilder = new StringBuilder();
        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filePath),
                StandardCharsets.UTF_8))) {
            stringBuilder.append("# 说明").append(JACGConstants.NEW_LINE).append(JACGConstants.NEW_LINE);

            if (CallTypeEnum.CTE_ITF == callTypeEnum) {
                stringBuilder.append("出现当前文件，说明接口调用对应实现类的方法调用存在一对多的方法调用");
            } else {
                stringBuilder.append("出现当前文件，说明抽象父类调用对应子类的方法调用存在一对多的方法调用");
            }

            stringBuilder.append(JACGConstants.NEW_LINE).append(JACGConstants.NEW_LINE)
                    .append("可以使用以下SQL语句查找对应的方法调用并禁用，仅保留需要的调用关系").append(JACGConstants.NEW_LINE).append(JACGConstants.NEW_LINE)
                    .append(JACGConstants.FLAG_MD_CODE_SQL).append(JACGConstants.NEW_LINE)
                    // 生成提示信息中的查询SQL
                    .append(genNoticeSelectSql(callTypeEnum.getType())).append(JACGConstants.NEW_LINE)
                    // 生成提示信息中的更新为禁用SQL
                    .append(genNoticeUpdateDisableSql(callTypeEnum.getType())).append(JACGConstants.NEW_LINE);

            if (this instanceof RunnerGenAllGraph4Callee) {
                // 生成向上的方法调用完整调用链时，增加一个显示的update语句
                stringBuilder.append(JACGConstants.NEW_LINE).append(genNoticeSelectSql4Callee()).append(JACGConstants.NEW_LINE)
                        .append(genNoticeUpdateDisableSql4Callee()).append(JACGConstants.NEW_LINE);
            }

            stringBuilder.append(JACGConstants.FLAG_MD_CODE);

            for (String multiCallerFullMethod : multiCallerFullMethodList) {
                MultiCallInfo multiCallInfo = methodCallMap.get(multiCallerFullMethod);
                if (multiCallInfo == null || JACGUtil.isCollectionEmpty(multiCallInfo.getCalleeFullMethodSet())) {
                    logger.error("未查找到对应的一对多方法调用关系 {}", multiCallerFullMethod);
                    continue;
                }

                stringBuilder.append(JACGConstants.NEW_LINE).append(JACGConstants.NEW_LINE)
                        .append("## ").append(multiCallerFullMethod).append(JACGConstants.NEW_LINE).append(JACGConstants.NEW_LINE)
                        .append("- ").append(DC.MC_CALLER_METHOD_HASH).append(JACGConstants.NEW_LINE).append(JACGConstants.NEW_LINE)
                        .append(multiCallInfo.getCallerMethodHash()).append(JACGConstants.NEW_LINE).append(JACGConstants.NEW_LINE)
                        .append("- ").append(DC.MC_CALLEE_FULL_METHOD).append("（被调用的方法）").append(JACGConstants.NEW_LINE).append(JACGConstants.NEW_LINE)
                        .append(JACGConstants.FLAG_MD_CODE).append(JACGConstants.NEW_LINE);

                for (String calleeMethod : multiCallInfo.getCalleeFullMethodSet()) {
                    stringBuilder.append(calleeMethod).append(JACGConstants.NEW_LINE);
                }

                stringBuilder.append(JACGConstants.FLAG_MD_CODE);

                // 打印存在一对多的方法调用，自定义处理
                printMultiMethodCallCustom(multiCallInfo.getCallerMethodHash(), stringBuilder);
            }

            out.write(stringBuilder.toString());
        } catch (Exception e) {
            logger.error("error ", e);
        }
    }

    // 打印存在一对多的方法调用，自定义处理
    protected void printMultiMethodCallCustom(String callerMethodHash, StringBuilder stringBuilder) {
    }

    // 打印被禁用的方法调用
    private void printDisabledMethodCall(Map<String, MultiCallInfo> disabledMethodCallMap, CallTypeEnum callTypeEnum) {
        if (disabledMethodCallMap.isEmpty()) {
            logger.info("{} 不存在被禁用的方法调用，不打印相关信息", callTypeEnum);
            return;
        }

        String filePath;
        if (CallTypeEnum.CTE_ITF == callTypeEnum) {
            filePath = outputDirPrefix + File.separator + JACGConstants.NOTICE_DISABLED_ITF_MD;
        } else {
            filePath = outputDirPrefix + File.separator + JACGConstants.NOTICE_DISABLED_SCC_MD;
        }

        logger.info("{} 存在被禁用的方法调用，打印相关信息 {}", callTypeEnum, filePath);

        StringBuilder stringBuilder = new StringBuilder();
        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filePath),
                StandardCharsets.UTF_8))) {
            stringBuilder.append("# 说明").append(JACGConstants.NEW_LINE).append(JACGConstants.NEW_LINE);

            if (CallTypeEnum.CTE_ITF == callTypeEnum) {
                stringBuilder.append("出现当前文件，说明接口调用对应实现类的方法调用存在被禁用的方法调用");
            } else {
                stringBuilder.append("出现当前文件，说明抽象父类调用对应子类的方法调用存在被禁用的方法调用");
            }
            stringBuilder.append(JACGConstants.NEW_LINE).append(JACGConstants.NEW_LINE)
                    .append("可以使用以下SQL语句查找对应的方法调用并启用").append(JACGConstants.NEW_LINE).append(JACGConstants.NEW_LINE)
                    .append(JACGConstants.FLAG_MD_CODE_SQL).append(JACGConstants.NEW_LINE)
                    // 生成提示信息中的查询SQL
                    .append(genNoticeSelectSql(callTypeEnum.getType())).append(JACGConstants.NEW_LINE)
                    // 生成提示信息中的更新为禁用SQL
                    .append(genNoticeUpdateEnableSql(callTypeEnum.getType())).append(JACGConstants.NEW_LINE)
                    .append(JACGConstants.FLAG_MD_CODE);

            for (Map.Entry<String, MultiCallInfo> entry : disabledMethodCallMap.entrySet()) {
                String disabledCallerMethod = entry.getKey();

                MultiCallInfo multiCallInfo = entry.getValue();
                if (multiCallInfo == null || JACGUtil.isCollectionEmpty(multiCallInfo.getCalleeFullMethodSet())) {
                    logger.error("未查找到对应的被禁用方法调用关系 {}", disabledCallerMethod);
                    continue;
                }
                stringBuilder.append(JACGConstants.NEW_LINE).append(JACGConstants.NEW_LINE)
                        .append("## ").append(disabledCallerMethod).append(JACGConstants.NEW_LINE).append(JACGConstants.NEW_LINE)
                        .append("- ").append(DC.MC_CALLER_METHOD_HASH).append(JACGConstants.NEW_LINE).append(JACGConstants.NEW_LINE)
                        .append(multiCallInfo.getCallerMethodHash()).append(JACGConstants.NEW_LINE).append(JACGConstants.NEW_LINE)
                        .append("- ").append(DC.MC_CALLEE_FULL_METHOD).append("（被调用的方法）").append(JACGConstants.NEW_LINE).append(JACGConstants.NEW_LINE)
                        .append(JACGConstants.FLAG_MD_CODE).append(JACGConstants.NEW_LINE);
                for (String calleeMethod : multiCallInfo.getCalleeFullMethodSet()) {
                    stringBuilder.append(calleeMethod).append(JACGConstants.NEW_LINE);
                }
                stringBuilder.append(JACGConstants.FLAG_MD_CODE);
            }

            out.write(stringBuilder.toString());
        } catch (Exception e) {
            logger.error("error ", e);
        }
    }

    // 打印提示信息
    protected void printNoticeInfo() {
        printMultiMethodCall(itfMethodCallMap, itfMultiCallerFullMethodSet, CallTypeEnum.CTE_ITF);
        printMultiMethodCall(sccMethodCallMap, sccMultiCallerFullMethodSet, CallTypeEnum.CTE_SCC);
        printDisabledMethodCall(disabledItfMethodCallMap, CallTypeEnum.CTE_ITF);
        printDisabledMethodCall(disabledSccMethodCallMap, CallTypeEnum.CTE_SCC);
    }

    // 生成提示信息中的查询SQL
    private String genNoticeSelectSql(String callType) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("select * from ")
                .append(JACGConstants.TABLE_PREFIX_METHOD_CALL).append(confInfo.getAppName())
                .append(" where ").append(DC.MC_CALL_TYPE).append(" = '").append(callType)
                .append("' and ").append(DC.MC_CALLER_METHOD_HASH).append(" = '';");
        return stringBuilder.toString();
    }

    private String genNoticeSelectSql4Callee() {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("select * from ")
                .append(JACGConstants.TABLE_PREFIX_METHOD_CALL).append(confInfo.getAppName())
                .append(" where ").append(DC.MC_CALLEE_METHOD_HASH).append(" = '';");
        return stringBuilder.toString();
    }

    // 生成提示信息中的更新为禁用SQL
    private String genNoticeUpdateDisableSql(String callType) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("update ")
                .append(JACGConstants.TABLE_PREFIX_METHOD_CALL).append(confInfo.getAppName())
                .append(" set ").append(DC.MC_ENABLED)
                .append(" = ").append(JACGConstants.DISABLED)
                .append(" where ")
                .append(DC.MC_CALL_TYPE).append(" = '").append(callType)
                .append("' and ").append(DC.MC_CALLER_METHOD_HASH).append(" = '' and ")
                .append(DC.MC_CALLEE_FULL_METHOD).append(" <> '';");
        return stringBuilder.toString();
    }

    private String genNoticeUpdateDisableSql4Callee() {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("update ")
                .append(JACGConstants.TABLE_PREFIX_METHOD_CALL).append(confInfo.getAppName())
                .append(" set ").append(DC.MC_ENABLED)
                .append(" = ").append(JACGConstants.DISABLED)
                .append(" where ").append(DC.MC_CALLEE_METHOD_HASH).append(" = '' and ")
                .append(DC.MC_CALLER_FULL_METHOD).append(" <> '';");
        return stringBuilder.toString();
    }

    // 生成提示信息中的更新为启用SQL
    private String genNoticeUpdateEnableSql(String callType) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("update ")
                .append(JACGConstants.TABLE_PREFIX_METHOD_CALL).append(confInfo.getAppName())
                .append(" set ").append(DC.MC_ENABLED)
                .append(" = ").append(JACGConstants.ENABLED)
                .append(" where ")
                .append(DC.MC_CALL_TYPE).append(" = '").append(callType)
                .append("' and ").append(DC.MC_CALLER_METHOD_HASH).append(" = '' and ")
                .append(DC.MC_CALLEE_FULL_METHOD).append(" = '';");
        return stringBuilder.toString();
    }

    /**
     * 检查Jar包文件是否有更新
     *
     * @return true: 有更新，false: 没有更新
     */
    protected boolean checkJarFileUpdated() {
        if (System.getProperty(JACGConstants.PROPERTY_SKIP_CHECK_JAR_FILE_UPDATED) != null) {
            logger.info("已在启动参数中指定 -D{}=xx，不检查Jar包文件是否有更新", JACGConstants.PROPERTY_SKIP_CHECK_JAR_FILE_UPDATED);
            return false;
        }

        Map<String, Map<String, Object>> jarInfoMap = queryJarFileInfo();
        if (JACGUtil.isMapEmpty(jarInfoMap)) {
            return false;
        }

        logger.info("检查Jar包文件是否有更新 {}", confInfo.getCallGraphJarList());

        String[] array = getJarArray();
        for (String jarName : array) {
            if (!JACGFileUtil.isFileExists(jarName)) {
                continue;
            }

            String jarFilePath = JACGFileUtil.getCanonicalPath(jarName);
            if (jarFilePath == null) {
                logger.error("获取文件路径失败: {}", jarName);
                return true;
            }

            String jarPathHash = JACGUtil.genHashWithLen(jarFilePath);
            Map<String, Object> jarInfo = jarInfoMap.get(jarPathHash);
            if (jarInfo == null) {
                String jarFullPath = jarName.equals(jarFilePath) ? "" : jarFilePath;
                logger.error("指定的Jar包未导入数据库中，请先执行 TestRunnerWriteDb 或 TestRunnerWriteDbJavaCGRecordAll 类导入数据库\n{} {}\n假如不需要检查Jar包文件是否有更新，可在启动参数中指定 -D{}=任意值",
                        jarName, jarFullPath, JACGConstants.PROPERTY_SKIP_CHECK_JAR_FILE_UPDATED);
                return true;
            }

            long lastModified = JACGFileUtil.getFileLastModified(jarFilePath);
            String lastModifiedStr = String.valueOf(lastModified);
            if (!lastModifiedStr.equals(jarInfo.get(DC.JI_LAST_MODIFIED))) {
                String jarFileHash = JACGFileUtil.getFileMd5(jarFilePath);
                if (!StringUtils.equals(jarFileHash, (String) jarInfo.get(DC.JI_JAR_HASH))) {
                    String jarFullPath = jarName.equals(jarFilePath) ? "" : jarFilePath;
                    logger.error("指定的Jar包文件内容有变化，请先执行 TestRunnerWriteDb 或 TestRunnerWriteDbJavaCGRecordAll 类导入数据库\n{} {} {}\n假如不需要检查Jar包文件是否有更新，可在启动参数中指定 -D{}=任意值",
                            new Date(lastModified), jarName, jarFullPath, JACGConstants.PROPERTY_SKIP_CHECK_JAR_FILE_UPDATED);
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    protected boolean checkH2DbFile() {
        File h2DbFile = getH2DbFile();
        if (!h2DbFile.exists()) {
            logger.error("H2数据库文件不存在，请先执行 TestRunnerWriteDb 或 TestRunnerWriteDbJavaCGRecordAll 类导入数据库 {}", JACGFileUtil.getCanonicalPath(h2DbFile));
            return false;
        }

        // 数据库文件存在
        if (!h2DbFile.isFile()) {
            logger.error("H2数据库文件不是文件 {}", JACGFileUtil.getCanonicalPath(h2DbFile));
            return false;
        }

        // 检查H2数据库文件是否可写
        return checkH2DbFileWritable(h2DbFile);
    }

    private Map<String, Map<String, Object>> queryJarFileInfo() {
        String sqlKey = JACGConstants.SQL_KEY_JI_QUERY_JAR_INFO;
        String sql = dbOperWrapper.getCachedSql(sqlKey);
        if (sql == null) {
            sql = "select * from " + JACGConstants.TABLE_PREFIX_JAR_INFO + confInfo.getAppName();
            dbOperWrapper.cacheSql(sqlKey, sql);
        }

        List<Map<String, Object>> list = dbOperator.queryList(sql, new Object[]{});
        if (JACGUtil.isCollectionEmpty(list)) {
            logger.error("查询到Jar包信息为空");
            return null;
        }

        Map<String, Map<String, Object>> rtnMap = new HashMap<>(list.size());

        for (Map<String, Object> map : list) {
            String jarPathHash = (String) map.get(DC.JI_JAR_PATH_HASH);
            rtnMap.putIfAbsent(jarPathHash, map);
        }

        return rtnMap;
    }

    /**
     * 获取本次执行时的输出目录
     *
     * @return null: 执行失败，非null: 执行成功
     */
    public String getSuccessOutputDir() {
        if (someTaskFail) {
            return null;
        }
        return outputDirPrefix;
    }

    /**
     * 重新设置线程数，若当前线程数需要调大则修改
     *
     * @param taskNum 任务数量
     */
    protected void resetPoolSize(int taskNum) {
        int currentPoolSize = confInfo.getThreadNum();
        int newPoolSize = Math.min(confInfo.getOriginalThreadNum(), taskNum);

        if (currentPoolSize >= newPoolSize) {
            // 当前线程数比准备修改的线程数大，不处理
            return;
        }

        confInfo.setThreadNum(newPoolSize);
        threadPoolExecutor.setCorePoolSize(newPoolSize);
        threadPoolExecutor.setMaximumPoolSize(newPoolSize);

        dbOperator.setMaxPoolSize(newPoolSize);
    }

    // 添加用于添加对方法上的注解进行处理的类
    protected boolean addMethodAnnotationHandlerExtensions() {
        if (!confInfo.isShowMethodAnnotation()) {
            return true;
        }

        Set<String> methodAnnotationHandlerClasses = configureWrapper.getOtherConfigSet(OtherConfigFileUseSetEnum.OCFUSE_EXTENSIONS_METHOD_ANNOTATION_HANDLER);
        if (JACGUtil.isCollectionEmpty(methodAnnotationHandlerClasses)) {
            annotationHandlerList = new ArrayList<>(1);
            // 添加默认的处理类
            annotationHandlerList.add(new DefaultAnnotationHandler());
            return true;
        }

        annotationHandlerList = new ArrayList<>(methodAnnotationHandlerClasses.size());
        try {
            for (String extensionClass : methodAnnotationHandlerClasses) {
                AbstractAnnotationHandler annotationHandler = JACGUtil.getClassObject(extensionClass, AbstractAnnotationHandler.class);
                if (annotationHandler == null) {
                    return false;
                }
                annotationHandler.setAnnotationStorage(annotationStorage);
                annotationHandlerList.add(annotationHandler);
            }
            // 在最后添加默认的处理类
            annotationHandlerList.add(new DefaultAnnotationHandler());
            return true;
        } catch (Exception e) {
            logger.error("error ", e);
            return false;
        }
    }

    /**
     * 获取方法对应的注解信息
     *
     * @param methodHash 完整方法HASH+长度
     * @return
     */
    protected String getMethodAnnotationInfo(String methodHash) {
        // 根据完整方法HASH+长度获取对应的注解信息
        Map<String, Map<String, BaseAnnotationAttribute>> methodAnnotationMap = annotationStorage.getAnnotationMap4Method(methodHash);
        if (methodAnnotationMap == null) {
            // 当前方法上没有注解
            return "";
        }

        // 当前方法上有注解
        String existedAnnotationInfo = methodAllAnnotationInfoMap.get(methodHash);
        if (existedAnnotationInfo != null) {
            // 当前方法对应的注解信息已查询过，直接使用
            return existedAnnotationInfo;
        }

        // 当前方法对应的注解信息未查询过
        StringBuilder stringBuilder = new StringBuilder();

        MethodWithAnnotationInfo methodWithAnnotationInfo = annotationStorage.getMethodWithAnnotationInfo(methodHash);
        if (methodWithAnnotationInfo != null) {
            // 遍历当前方法上的所有注解进行处理
            for (Map.Entry<String, Map<String, BaseAnnotationAttribute>> methodAnnotationMapEntry : methodAnnotationMap.entrySet()) {
                String annotationName = methodAnnotationMapEntry.getKey();
                // 遍历用于对方法上的注解进行处理的类
                for (AbstractAnnotationHandler annotationHandler : annotationHandlerList) {
                    if (!annotationHandler.checkHandleAnnotation(annotationName)) {
                        continue;
                    }

                    // 找到能够处理的类进行处理
                    String annotationInfo = annotationHandler.handleAnnotation(methodWithAnnotationInfo.getFullMethod(), methodWithAnnotationInfo.getFullClassName(),
                            annotationName, methodAnnotationMapEntry.getValue());
                    // 假如注解信息中包含了特定字符，会导致调用链文件行分隔时出现问题，因此将TAB替换为空格，半角@替换为全角＠
                    if (StringUtils.contains(annotationInfo, JACGConstants.FLAG_TAB)) {
                        logger.warn("注解内容中包含了TAB，将其替换为空格，请确认是否有影响 {}", annotationInfo);
                        annotationInfo = annotationInfo.replace(JACGConstants.FLAG_TAB, JACGConstants.FLAG_SPACE);
                    }
                    if (StringUtils.contains(annotationInfo, JACGConstants.FLAG_AT)) {
                        logger.warn("注解内容中包含了半角{}，将其替换为全角＠，请确认是否有影响 {}", JACGConstants.FLAG_AT, annotationInfo);
                        annotationInfo = annotationInfo.replace(JACGConstants.FLAG_AT, "＠");
                    }

                    // 注解信息以@开头，在以上方法中不需要返回以@开头
                    stringBuilder.append(JACGConstants.FLAG_AT).append(annotationInfo);
                    break;
                }
            }
        }

        String allAnnotationInfo = stringBuilder.toString();
        methodAllAnnotationInfoMap.put(methodHash, allAnnotationInfo);
        return allAnnotationInfo;
    }

    // 生成映射文件
    protected void writeMappingFile() {
        String mappingFilePath = outputDirPrefix + File.separator + JACGConstants.FILE_MAPPING_NAME;
        logger.info("生成映射文件 {}", mappingFilePath);

        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(mappingFilePath), StandardCharsets.UTF_8))) {
            out.write("# 配置文件中指定的任务信息" + JACGConstants.FLAG_TAB + "生成结果文件路径" + JACGConstants.NEW_LINE);
            for (Map.Entry<String, String> entry : methodInConfAndFileMap.entrySet()) {
                // 以TAB作为分隔，因为key中可能存在空格
                out.write(entry.getKey() + JACGConstants.FLAG_TAB + entry.getValue() + JACGConstants.NEW_LINE);
            }
        } catch (Exception e) {
            logger.error("error ", e);
        }
    }

    // 执行通过代码行号获取调用者方法
    protected FindMethodInfo doFindCallerMethodByLineNumber(String className, int methodLineNum) {
        String sqlKey = JACGConstants.SQL_KEY_MLN_QUERY_METHOD;
        String sql = dbOperWrapper.getCachedSql(sqlKey);
        if (sql == null) {
            sql = "select " + JACGSqlUtil.joinColumns(DC.MLN_METHOD_HASH, DC.MLN_FULL_METHOD) +
                    " from " + JACGConstants.TABLE_PREFIX_METHOD_LINE_NUMBER + confInfo.getAppName() +
                    " where " + DC.MLN_SIMPLE_CLASS_NAME + " = ? and " +
                    DC.MLN_MIN_LINE_NUMBER + " <= ? and " +
                    DC.MLN_MAX_LINE_NUMBER + " >= ? limit 1";
            dbOperWrapper.cacheSql(sqlKey, sql);
        }

        Map<String, Object> map = dbOperator.queryOneRow(sql, new Object[]{className, methodLineNum, methodLineNum});
        if (map == null) {
            return FindMethodInfo.genFindMethodInfoFail();
        }

        if (JACGUtil.isMapEmpty(map)) {
            logger.warn("指定类的代码行号未查找到对应方法，请检查，可能因为以下原因\n" +
                    "1. 指定的类所在的jar包未在配置文件 {} 参数 {} 中指定\n" +
                    "2. 指定的方法是接口中未实现的方法\n" +
                    "3. 指定的方法是抽象方法\n" +
                    "{} {}", JACGConstants.FILE_CONFIG, ConfigKeyEnum.CKE_CALL_GRAPH_JAR_LIST, className, methodLineNum);
            // 在此不生成空文件
            return FindMethodInfo.genFindMethodInfoGenEmptyFile();
        }

        // 指定类的代码行号查找到对应方法
        return FindMethodInfo.genFindMethodInfoSuccess((String) map.get(DC.MLN_METHOD_HASH), (String) map.get(DC.MLN_FULL_METHOD));
    }
}
