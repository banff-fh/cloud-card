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
            	if(origin.equals("*")){
            		 httpResponse.setHeader("Access-Control-Allow-Origin", "*"); 
            		 // 如果allowList里面配置了*，就不用去匹配了，直接allow
            		 break;
            	}
                if(curOrigin.equals(origin)) {  
                    httpResponse.setHeader("Access-Control-Allow-Origin", curOrigin);
                    break;
                }
            }  
        } 
        chain.doFilter(request, response);
	}

	@Override
	public void init(FilterConfig config) throws ServletException {
		  String allowedOrigin = config.getInitParameter("allowedOrigin");
		  allowList = StringUtil.split(allowedOrigin, ";");
		  
	}

}
