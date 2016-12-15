package com.banfftech.cloudcard.common;

import java.io.IOException;
import java.util.Map;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilHttp;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilValidate;

public class CloudCardLogFilter implements Filter {

	 public static final String module = "cloudcard";
	 
	 
	 
	@Override
	public void destroy() {
		// TODO Auto-generated method stub

	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		HttpServletRequest httpRequest = (HttpServletRequest) request;  

        Map<String,Object> inputMap = UtilHttp.getParameterMap(httpRequest);
        String token = null;
        if(inputMap.containsKey("token")){
            // This was removed for security reason
        	token = (String) inputMap.remove("token");
        }

        if (Debug.infoOn()) {
			StringBuilder logsb = new StringBuilder(httpRequest.getPathInfo());
			logsb.append(System.getProperty("line.separator"));
			logsb.append("input:");
			logsb.append(System.getProperty("line.separator"));
			logsb.append(UtilMisc.printMap(inputMap));
			if (UtilValidate.isNotEmpty(token)) {
				logsb.append("For security reasons, a token field was removed when this log was logged.");
				logsb.append(System.getProperty("line.separator"));
			}
			Debug.logInfo(logsb.toString(), module);
		}

        chain.doFilter(request, response);
        
        // I can't get the  response content ,
        // so the output log was coded in  com.banfftech.cloudcard.common.CommonEvents:131
	}

	@Override
	public void init(FilterConfig config) throws ServletException {
		// TODO Auto-generated method stub
		  
	}

}
