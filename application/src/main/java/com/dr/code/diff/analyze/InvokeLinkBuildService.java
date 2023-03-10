package com.dr.code.diff.analyze;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson.JSON;
import com.dr.code.diff.analyze.bean.AdapterContext;
import com.dr.code.diff.analyze.bean.MethodInfo;
import com.dr.code.diff.analyze.link.CallChainClassVisitor;
import com.dr.code.diff.enums.MethodNodeTypeEnum;
import com.dr.code.diff.util.StringUtil;
import com.dr.common.errorcode.BaseCode;
import com.dr.common.errorcode.BizCode;
import com.dr.common.exception.BizException;
import com.dr.common.log.LoggerUtil;
import com.dr.common.utils.mapper.OrikaMapperUtils;
import com.google.common.collect.Lists;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.springframework.stereotype.Service;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * @Package: com.dr.code.diff.analyze
 * @Description: 基于ASM获取调用链
 * @Author: rayduan
 * @CreateDate: 2023/2/24 15:55
 * @Version: 1.0
 * <p>
 */
@Service
@Slf4j
public class InvokeLinkBuildService {


    /**
     * ant路径匹配器
     */
    private static final AntPathMatcher antPathMatcher = new AntPathMatcher();
    private static final String URL_PREFIX = "/";
    @Resource(name = "asyncExecutor")
    private Executor executor;

    /**
     * get方法调用链接
     *
     * @param classesDir     类的目录
     * @param excludeClasses 排除类
     * @return {@link List}<{@link MethodInfo}>
     */
    public Map<MethodNodeTypeEnum, List<MethodInfo>> getMethodsInvokeLink(List<String> classesDir, List<String> excludeClasses) {
        LoggerUtil.info(log, "开始获取调用链");
        if (CollectionUtils.isEmpty(classesDir)) {
            LoggerUtil.info(log, "请正确填写class文件地址");
            return Collections.emptyMap();
        }
        List<MethodInfo> allMethods = new ArrayList<>();
        classesDir.forEach(
                e -> {
                    try {
                        //获取项目的groupId用于调用链降噪
                        String pomPath = StringUtil.connectPath(e, "pom.xml");
                        MavenXpp3Reader reader = new MavenXpp3Reader();
                        String groupId = "";
                        try {
                            Model model = reader.read(new FileReader(pomPath));
                            groupId = model.getGroupId();
                            if (!StringUtils.isEmpty(groupId)) {
                                groupId = groupId.replace(".", "/");
                            }
                        } catch (FileNotFoundException fileNotFoundException) {
                            LoggerUtil.error(log, "非maven项目或者获取pom路径有误!");
                        }
                        AdapterContext adapterContext = AdapterContext.builder().basePackagePath(groupId).build();
                        //获取一个目录下的所有class文件
                        List<File> files = FileUtil.loopFiles(new File(e), pathname -> pathname.getName().endsWith(".class"));
                        if (CollectionUtils.isEmpty(files)) {
                            return;
                        }
                        //并发获取每个方法的调用方法
                        List<CompletableFuture<List<MethodInfo>>> priceFuture = files.stream()
                                .map(item -> CompletableFuture.supplyAsync(() -> getSingleClassMethodsInvoke(item, excludeClasses, adapterContext), executor))
                                .collect(Collectors.toList());
                        CompletableFuture.allOf(priceFuture.toArray(new CompletableFuture[0])).join();
                        List<MethodInfo> list = priceFuture.stream().map(CompletableFuture::join).flatMap(Collection::stream).filter(Objects::nonNull).collect(Collectors.toList());
                        allMethods.addAll(list);
                    } catch (Exception ex) {
                        throw new BizException(BizCode.GET_METHOD_INVOKE_LINK_FAIL);
                    }
                }
        );
        LoggerUtil.info(log, "调用链获取完成");
        return buildMethodLink(allMethods);
    }


    /**
     * 单个类的方法调用
     *
     * @param file 文件
     * @return {@link List}<{@link MethodInfo}>
     */
    public List<MethodInfo> getSingleClassMethodsInvoke(File file, List<String> excludeClasses, AdapterContext adapterContext) {
        if (!CollectionUtils.isEmpty(excludeClasses)) {
            boolean excludeFlag = excludeClasses.stream().allMatch(e -> antPathMatcher.match(e, file.getPath()));
            if (excludeFlag) {
                return Collections.emptyList();
            }
        }
        List<MethodInfo> list = new ArrayList<>();
        try {
            ClassReader cr = new ClassReader(Files.newInputStream(file.toPath()));
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            CallChainClassVisitor cv = new CallChainClassVisitor(cw, list, adapterContext);
            cr.accept(cv, ClassReader.SKIP_FRAMES);
        } catch (Exception e) {
            LoggerUtil.error(log, "获取调用链失败", e.getMessage());
            return Collections.emptyList();
        }
        return list;
    }


    /**
     * 构建方法链接tree
     *
     * @param allMethods 所有方法
     * @return {@link List}<{@link MethodInfo}>
     */
    public Map<MethodNodeTypeEnum, List<MethodInfo>> buildMethodLink(List<MethodInfo> allMethods) {
        if (CollectionUtils.isEmpty(allMethods)) {
            return Collections.emptyMap();
        }
        Map<MethodNodeTypeEnum, List<MethodInfo>> map = new HashMap<>();
        Map<String, List<MethodInfo>> methodCallMap = allMethods.stream().collect(Collectors.toMap(MethodInfo::getMethodSign, MethodInfo::getCallerMethods, (v1, v2) -> v2));
        //按照抽象方法的类名和其实现方法分组
        Map<String, List<MethodInfo>> abstractSubMethodMap = allMethods.stream().filter(e -> StringUtils.isNotBlank(e.getClassInfo().getSuperClassName())).collect(Collectors.groupingBy(e -> e.getClassInfo().getSuperClassName()));
        List<MethodInfo> allInterFaceList = new ArrayList<>();
        List<MethodInfo> interFaceList = allMethods.stream().filter(e -> !CollectionUtils.isEmpty((e.getClassInfo().getInterfacesClassNames()))).collect(Collectors.toList());
        interFaceList.forEach(
                e -> {
                    List<String> interfacesClassNames = e.getClassInfo().getInterfacesClassNames();
                    interfacesClassNames.forEach(
                            i -> {
                                MethodInfo m = OrikaMapperUtils.map(e, MethodInfo.class);
                                m.getClassInfo().setInterfacesClassNames(Lists.newArrayList(i));
                                allInterFaceList.add(m);
                            }
                    );
                }
        );
        Map<String, List<MethodInfo>> interFaceMethodMap = allInterFaceList.stream().collect(Collectors.groupingBy(e -> e.getClassInfo().getInterfacesClassNames().get(0)));
        //将抽象类和接口的实现合并
        abstractSubMethodMap.putAll(interFaceMethodMap);
        //获取所有的抽象或者接口方法
        List<String> abstractMethod = allMethods.stream().filter(MethodInfo::getAbstractFlag).map(MethodInfo::getMethodSign).collect(Collectors.toList());
        //先找出起点方法
        Map<MethodNodeTypeEnum, List<MethodInfo>> methodTypeMap = allMethods.stream().collect(Collectors.groupingBy(MethodInfo::getMethodNodeTypeEnum));
        //1.http接口方法
        List<MethodInfo> httpMethodInfoList = methodTypeMap.get(MethodNodeTypeEnum.HTTP);
        if (!CollectionUtils.isEmpty(httpMethodInfoList)) {
            //初始化子节点
            httpMethodInfoList.forEach(e -> {
                //重置调用节点，避免循环引用
                e.setCallerMethods(null);
                String controllerMappingUrl = e.getClassInfo().getRequestUrl();
                String methodMappingUrl = e.getMappingUrl();
                if (StringUtils.isBlank(controllerMappingUrl)) {
                    controllerMappingUrl = "";
                }
                if (StringUtils.isBlank(methodMappingUrl)) {
                    methodMappingUrl = "";
                }
                String methodUrl = "";
                if (controllerMappingUrl.endsWith(URL_PREFIX) && methodMappingUrl.startsWith(URL_PREFIX)) {
                    methodUrl = controllerMappingUrl + methodMappingUrl;
                } else {
                    methodUrl = StringUtil.connectPath(controllerMappingUrl, methodMappingUrl);
                }
                e.setMappingUrl(methodUrl);
            });
        }
        //2.dubbo方法
        List<MethodInfo> dubboMethodInfoList = methodTypeMap.get(MethodNodeTypeEnum.DUBBO);
        if (!CollectionUtils.isEmpty(dubboMethodInfoList)) {
            //初始化子节点
            dubboMethodInfoList.forEach(e -> e.setCallerMethods(null));
        }
        //获取http的调用链
        loadChildren(methodCallMap, httpMethodInfoList, abstractSubMethodMap, abstractMethod);
        //获取dubbo的调用链
        loadChildren(methodCallMap, dubboMethodInfoList, abstractSubMethodMap, abstractMethod);
        map.put(MethodNodeTypeEnum.HTTP, httpMethodInfoList);
        map.put(MethodNodeTypeEnum.DUBBO, dubboMethodInfoList);
        return map;
    }


    /**
     * 构建父子节点
     *
     * @param map                  所有方法调用map
     * @param parentList           父列表
     * @param abstractSubMethodMap 抽象子映射方法
     */
    private void loadChildren(Map<String, List<MethodInfo>> map, List<MethodInfo> parentList, Map<String, List<MethodInfo>> abstractSubMethodMap, List<String> abstractMethod) {
        if (CollectionUtils.isEmpty(parentList) || CollectionUtils.isEmpty(map)) {
            return;
        }
        parentList.forEach(
                e -> {
                    //如果方法是抽象方法，则将其所有子类匹配的方法都加入链路中
                    if (abstractMethod.contains(e.getMethodSign())) {
                        //抽象和接口类处理
                        setSubMethod(e, abstractSubMethodMap);
                    } else {
                        List<MethodInfo> subList = map.get(e.getMethodSign());
                        if (!CollectionUtils.isEmpty(subList)) {
                            //校验父节点中是否已经存在的子节点
                            List<MethodInfo> vaildSubList = subList.stream().filter(s -> !JSON.toJSONString(e).contains(s.getMethodSign())).collect(Collectors.toList());
                            if (!CollectionUtils.isEmpty(vaildSubList)) {
                                e.setCallerMethods(vaildSubList);
                            }
                        }
                    }
                    if (CollUtil.isNotEmpty(e.getCallerMethods())) {
                        // 设置子节点
                        loadChildren(map, e.getCallerMethods(), abstractSubMethodMap, abstractMethod);
                    }
                }
        );
    }

    /**
     * 设置子方法
     *
     * @param methodInfo           方法信息
     * @param abstractSubMethodMap 抽象子映射方法
     */
    private void setSubMethod(MethodInfo methodInfo, Map<String, List<MethodInfo>> abstractSubMethodMap) {
        if (!CollectionUtils.isEmpty(abstractSubMethodMap) && abstractSubMethodMap.containsKey(methodInfo.getClassInfo().getClassName())) {
            List<MethodInfo> methodInfoList = abstractSubMethodMap.get(methodInfo.getClassInfo().getClassName());
            if (CollectionUtils.isEmpty(methodInfoList)) {
                return;
            }
            methodInfoList.forEach(e -> e.setCallerMethods(null));
            //过滤出实现类匹配的方法
            methodInfoList = methodInfoList.stream().filter(e ->
                    e.getMethodName().equals(methodInfo.getMethodName()) && String.join(",", e.getMethodParams()).equals(String.join(",", methodInfo.getMethodParams()))
            ).collect(Collectors.toList());
            methodInfo.setCallerMethods(methodInfoList);
        }
    }
}
