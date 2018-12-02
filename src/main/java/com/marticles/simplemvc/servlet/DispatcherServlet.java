package com.marticles.simplemvc.servlet;

import com.marticles.simplemvc.annotation.*;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.regex.Pattern;

public class DispatcherServlet extends HttpServlet {

    private Properties properties = new Properties();

    private List<String> classes = new ArrayList<String>();

    // 使用Map来完成IOC容器
    private Map<String, Object> ioc = new HashMap<String, Object>();

    private  List<Handler> handlerMapping = new ArrayList<Handler>();

    @Override
    public void init(ServletConfig config) throws ServletException {
        // 1. 加载Spring配置文件application.properties
        doLoadConfig(config.getInitParameter("contextConfigLocation"));

        // 2. 扫描所有满足条件的类(@Controller、@Service)
        doScanner(properties.getProperty("sacnPackage"));

        // 3. 初始化类，装载至IOC容器中
        doInstance();

        // 4. 依赖注入
        doAutowired();

        // 5. 构造HandlerMapping映射关系，将一个URL映射为一个Method
        initHandlerMapping();

        // 6. 等待用户请求(GET/POST)，匹配URL，定位方法，反射调用执行


        // 7. 返回处理结果
        System.out.println("项目启动：" + config);
    }


    private void doLoadConfig(String location) {
        InputStream fis = this.getClass().getClassLoader().getResourceAsStream(location);
        try {
            properties.load(fis);
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            fis.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private void doScanner(String packageName) {
        // 从Class文件目录下找到所有Class
        URL url = this.getClass().getClassLoader().getResource("/" + packageName.replaceAll("\\.", "/"));
        File dir = new File(url.getFile());

        for (File file : dir.listFiles()) {
            if (file.isDirectory()) {
                doScanner(packageName + "." + file.getName());
            } else {
                String className = packageName + "." + file.getName().replace(".class", "");
                classes.add(className);
            }

        }

    }

    private void doInstance() {
        if (classes.isEmpty()) {
            return;
        }
        try {
            for (String className : classes) {
                Class<?> clazz = Class.forName(className);
                if (clazz.isAnnotationPresent(Controller.class)) {
                    // beanName默认首字母小写
                    String beanName = lowerFirst(clazz.getSimpleName());
                    // 保存至IOC容器中
                    ioc.put(beanName, clazz.newInstance());


                } else if (clazz.isAnnotationPresent(Service.class)) {
                    // service有接口
                    Service service = clazz.getAnnotation(Service.class);
                    String beanName = service.value();

                    if (!"".equals(beanName.trim())) {
                        ioc.put(beanName, clazz.newInstance());
                    } else {
                        beanName = lowerFirst(clazz.getSimpleName());
                        ioc.put(beanName, clazz.newInstance());
                    }

                    Class<?>[] interfaces = clazz.getInterfaces();
                    for (Class<?> c : interfaces) {
                        ioc.put(c.getName(), clazz.newInstance());
                    }
                } else {
                    continue;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private void doAutowired() {
        if (ioc.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            // 把类的所有属性全部取出来
            Field[] fields = entry.getValue().getClass().getDeclaredFields();

            for (Field field : fields) {
                if (!field.isAnnotationPresent(Autowired.class)) {
                    continue;
                }
                Autowired autowired = field.getAnnotation(Autowired.class);
                String beanName = autowired.value().trim();
                if ("".equals(beanName)) {
                    beanName = field.getType().getName();
                }
                // 取消权限检查，注入private
                field.setAccessible(true);

                try {
                    field.set(entry.getValue(), ioc.get(beanName));
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                    continue;
                }
            }
        }
    }

    private void initHandlerMapping() {
        String url = "";
        if (ioc.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            Class<?> clazz = entry.getValue().getClass();

            // 只有加了@Controller注解的类才有@RequestMapping注解
            if (!clazz.isAnnotationPresent(Controller.class)) {
                continue;
            }
            // 类的URL
            if (clazz.isAnnotationPresent(RequestMapping.class)) {
                RequestMapping requestMapping = clazz.getAnnotation(RequestMapping.class);
                url = requestMapping.value();
            }

            Method[] methods = clazz.getMethods();
            for (Method method : methods) {
                if (!method.isAnnotationPresent(RequestMapping.class)) {
                    continue;
                }
                RequestMapping requestMapping = method.getAnnotation(RequestMapping.class);
                String murl = url + requestMapping.value().replaceAll("/+", "/");
                Pattern pattern = Pattern.compile(murl.replaceAll("/+", "/"));
                handlerMapping.add(new Handler(entry.getValue(), method, pattern));
            }
        }

    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        this.doPost(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        try {
            doDispatch(request, response);
        } catch (Exception e) {
            response.getWriter().write("500" + Arrays.toString(e.getStackTrace()));

        }
        response.getWriter().flush();
    }

    private void doDispatch(HttpServletRequest request, HttpServletResponse response) throws IOException {

        Handler handler = getHandler(request);
        if(null == handler) {

            try {
                response.getWriter().write("404 - Page Not Found");
            } catch (IOException e) {
                e.printStackTrace();
            }
            return;
        }
        // 获取方法的参数列表
        Class<?>[] paramTypes = handler.method.getParameterTypes();
        // 保存所有需要自动赋值的参数值
        Object[] paramValues = new Object[paramTypes.length];

        Map<String, String[]> params = request.getParameterMap();
        for(Map.Entry<String, String[]> param : params.entrySet()){
            String value = Arrays.toString(param.getValue()).replaceAll("\\[|\\]","").replaceAll(",\\s","");
            if(!handler.paramIndexMapping.containsKey(param.getKey())){
                continue;
            }
            int index = handler.paramIndexMapping.get(param.getKey());
            paramValues[index] = convert(paramTypes[index], value);
        }

        int reqIndex = handler.paramIndexMapping.get(HttpServletRequest.class.getName());
        paramValues[reqIndex] = request;
        int respIndex = handler.paramIndexMapping.get(HttpServletResponse.class.getName());
        paramValues[respIndex] = response;

        try {
            handler.method.invoke(handler.controller, paramValues);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (IllegalArgumentException e) {
            try {
                System.out.println("请求参数错误");
                response.getWriter().write("404 - Page Not Found");
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }

    }

    private Handler getHandler(HttpServletRequest request){
        if(handlerMapping.isEmpty()) {
            return null;
        }
        String url = request.getRequestURI();
        String contextPath = request.getContextPath();
        url = url.replaceAll(contextPath, "").replaceAll("/+", "/");
        for(Handler handler : handlerMapping){
            try {
                if(handler.pattern.matcher(url).matches()){
                    return handler;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    private String lowerFirst(String beanName) {
        char[] chars = beanName.toCharArray();
        chars[0] += 32;
        return String.valueOf(chars);
    }

    private Object convert(Class<?> paramType, String value) {
        if(Integer.class == paramType){
            return Integer.valueOf(value);
        }
        return value;
    }

    class Handler {
        Object controller;  // 方法对应的实例
        Method method;      // 映射的方法
        Pattern pattern;    // 用于方法映射，因为RequestMapping用的是正则，不是字符串
        Map<String, Integer> paramIndexMapping = new HashMap<String, Integer>();    // 参数顺序

        public Handler(Object controller, Method method, Pattern pattern) {
            this.controller = controller;
            this.method = method;
            this.pattern = pattern;
            putParamIndexMapping(method);
        }

        public void putParamIndexMapping(Method method) {
            Annotation[][] anns = method.getParameterAnnotations();

            // 提取方法中带了注解的参数
            for (int i = 0; i < anns.length; i++) {
                for (Annotation an : anns[i]) {
                    if (an instanceof RequestParam) {
                        String paramName = ((RequestParam) an).value();
                        if (!paramName.equals("")) {
                            paramIndexMapping.put(paramName, i);
                        }
                    }
                }
            }

            // 提取方法中Request和Response参数
            Class<?>[] paramTypes = method.getParameterTypes();
            for (int i = 0; i < paramTypes.length; i++) {
                Class<?> paramType = paramTypes[i];
                if (paramType == HttpServletRequest.class || paramType == HttpServletResponse.class) {
                    paramIndexMapping.put(paramType.getName(), i);
                }
            }
        }
    }
}
