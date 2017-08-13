/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package com.banfftech.cloudcard.common;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.DelegatorFactory;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.security.Security;
import org.ofbiz.security.SecurityConfigurationException;
import org.ofbiz.security.SecurityFactory;
import org.ofbiz.security.authz.Authorization;
import org.ofbiz.security.authz.AuthorizationFactory;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.webapp.control.ContextFilter;
import org.ofbiz.webapp.control.LoginWorker;
import org.ofbiz.webapp.stats.VisitHandler;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;

/**
 * Common Workers
 */
public class CloudCardLoginWorker {

    public final static String module = CloudCardLoginWorker.class.getName();
    public static final String resourceWebapp = "SecurityextUiLabels";

    public static final String TOKEN_KEY_ATTR = "token";
    

    public static String checkTokenLogin(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession();
        Delegator delegator = (Delegator) request.getAttribute("delegator");

        Debug.logInfo("token verify...",module);
        String token = request.getParameter(TOKEN_KEY_ATTR);
        // 这种事件里面只能返回success, 后面的其它预处理事件会继续采用其它方式验证登录情况
        if (token == null) return "success"; 
        
        // 验证token
        Delegator defaultDelegator = DelegatorFactory.getDelegator("default");//万一出现多租户情况，应在主库中查配置
        String tokenSecret = EntityUtilProperties.getPropertyValue("cloudcard","token.secret", defaultDelegator);
        String iss = EntityUtilProperties.getPropertyValue("cloudcard","token.issuer",delegator);
        
        Map<String, Claim> claims;
        try {
        		JWTVerifier verifier = JWT.require(Algorithm.HMAC256(tokenSecret)).build();//验证token和发布者（云平台
			DecodedJWT jwt = verifier.verify(token);
			claims =  jwt.getClaims();
        }catch(TokenExpiredException e1){
            Debug.logInfo("token过期：" + e1.getMessage(),module);
            return "success";
        }catch(JWTVerificationException | IllegalStateException | IOException e) {
            Debug.logInfo("token没通过验证：" + e.getMessage(),module);
            return "success";
        }
        
        if(UtilValidate.isEmpty(claims)||UtilValidate.isEmpty(claims.get("user"))||UtilValidate.isEmpty(claims.get("delegatorName"))){
        	 Debug.logInfo("token invalid",module);
             return "success";
        }
        
        String userLoginId = claims.get("user").asString();
        String tokenDelegatorName = claims.get("delegatorName").asString();
        Delegator tokenDelegator = DelegatorFactory.getDelegator(tokenDelegatorName);
        GenericValue userLogin;
		try {
			userLogin = tokenDelegator.findByPrimaryKey("UserLogin", UtilMisc.toMap("userLoginId", userLoginId));
		} catch (GenericEntityException e) {
			Debug.logError("some thing wrong when verify the token:" + e.getMessage(), module);
			return "success";
		}
        
        if (userLogin != null) {
            //in case  in different tenants
            String currentDelegatorName = delegator.getDelegatorName();
            ServletContext servletContext = session.getServletContext();
            if (!currentDelegatorName.equals(tokenDelegatorName)) {
            	LocalDispatcher tokenDispatcher = ContextFilter.makeWebappDispatcher(servletContext, tokenDelegator);
                setWebContextObjects(request, response, tokenDelegator, tokenDispatcher);
            }
            // found userLogin, do the external login...

            // if the user is already logged in and the login is different, logout the other user
            GenericValue sessionUserLogin = (GenericValue) session.getAttribute("userLogin");
            if (sessionUserLogin != null) {
                if (sessionUserLogin.getString("userLoginId").equals(userLoginId)) {
                    // is the same user, just carry on...
                    return "success";
                }

                // logout the current user and login the new user...
                LoginWorker.logout(request, response);
                // ignore the return value; even if the operation failed we want to set the new UserLogin
            }

            LoginWorker.doBasicLogin(userLogin, request);
            
            //当token离到期时间少于多少秒，更新新的token，默认24小时（24*3600 = 86400L）
            long secondsBeforeUpdatetoken = Long.valueOf(EntityUtilProperties.getPropertyValue("cloudcard","token.secondsBeforeUpdate", "86400", defaultDelegator));
            
            long now = System.currentTimeMillis() / 1000L;  
            Long oldExp = Long.valueOf(String.valueOf(claims.get("exp")));

            if(oldExp - now < secondsBeforeUpdatetoken){
            	// 快要过期了，新生成token
            	long expirationTime = Long.valueOf(EntityUtilProperties.getPropertyValue("cloudcard","token.expirationTime","172800",defaultDelegator));
    			//开始时间
    			//Token到期时间
    			long exp = now + expirationTime; 
    			//生成Token
            Algorithm algorithm;
			try {
				algorithm = Algorithm.HMAC256(tokenSecret);
				token = JWT.create()
			    		.withIssuer(iss)
			    		.withIssuedAt(new Date(now))
			    		.withExpiresAt(new Date(exp))
			    		.withClaim("delegatorName", tokenDelegatorName)
			    		.withClaim("user",userLoginId)
			        .sign(algorithm);
			} catch (IllegalArgumentException | UnsupportedEncodingException e1) {
				Debug.logError(e1.getMessage(), module);
			}
          }
        } else {
            Debug.logWarning("Could not find userLogin for token: " + token, module);
        }
        
        return "success";
    }

    
    /**
     * Copy from org.ofbiz.webapp.control.LoginWorker
     * @param request
     * @param response
     * @param delegator
     * @param dispatcher
     */
    private static void setWebContextObjects(HttpServletRequest request, HttpServletResponse response, Delegator delegator, LocalDispatcher dispatcher) {
        HttpSession session = request.getSession();
        // NOTE: we do NOT want to set this in the servletContext, only in the request and session
        // We also need to setup the security and authz objects since they are dependent on the delegator
        Security security = null;
        try {
            security = SecurityFactory.getInstance(delegator);
        } catch (SecurityConfigurationException e) {
            Debug.logError(e, module);
        }
        Authorization authz = null;
        try {
            authz = AuthorizationFactory.getInstance(delegator);
        } catch (SecurityConfigurationException e) {
            Debug.logError(e, module);
        }

        request.setAttribute("delegator", delegator);
        request.setAttribute("dispatcher", dispatcher);
        request.setAttribute("security", security);
        request.setAttribute("authz", authz);

        session.setAttribute("delegatorName", delegator.getDelegatorName());
        session.setAttribute("delegator", delegator);
        session.setAttribute("dispatcher", dispatcher);
        session.setAttribute("security", security);
        session.setAttribute("authz", authz);

        // get rid of the visit info since it was pointing to the previous database, and get a new one
        session.removeAttribute("visitor");
        session.removeAttribute("visit");
        VisitHandler.getVisitor(request, response);
        VisitHandler.getVisit(session);
    }
}
