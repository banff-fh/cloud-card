package com.banfftech.cloudcard.common;

import java.io.IOException;
import java.util.List;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.StringUtil;
import org.ofbiz.base.util.UtilValidate;

public class CrossOriginFilter implements Filter {

	 public static final String module = CrossOriginFilter.class.getName();
	 
	 public List<String> allowList;
	 
	 
	@Override
	public void destroy() {
		// TODO Auto-generated method stub

	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		HttpServletRequest httpRequest = (HttpServletRequest) request;  
        HttpServletResponse httpResponse = (HttpServletResponse) response;  
        String curOrigin = httpRequest.getHeader("Origin");
        Debug.logInfo("Request Origin:" + curOrigin, module);
        if(curOrigin != null && UtilValidate.isNotEmpty(allowList)) {  
            for (String origin : allowList) {  
                if(curOrigin.equals(origin)) {  
                    httpResponse.setHeader("Access-Control-Allow-Origin", curOrigin);  
                }  
            }  
        } /*else { // 对于无来源的请求(比如在浏览器地址栏直接输入请求的)，那就只允许我们自己的机器可以吧  
            httpResponse.setHeader("Access-Control-Allow-Origin", "http://127.0.0.1");  
        }  
        
        
        httpResponse.setHeader("Access-Control-Allow-Origin", "*");*/
        chain.doFilter(request, response);
	}

	@Override
	public void init(FilterConfig config) throws ServletException {
		  String allowedOrigin = config.getInitParameter("allowedOrigin");
		  allowList = StringUtil.split(allowedOrigin, ";");
		  
	}

}
