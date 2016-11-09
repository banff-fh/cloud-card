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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.ofbiz.base.util.Debug;
import org.ofbiz.entity.GenericValue;

/**
 * Common Workers
 */
public class CloudCardLoginWorker {

    public final static String module = CloudCardLoginWorker.class.getName();
    public static final String resourceWebapp = "SecurityextUiLabels";

    public static final String EXTERNAL_LOGIN_KEY_ATTR = "token";

    public static String checkTokenLogin(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession();

        Debug.logInfo("ahhaahahahhah:=====",module);
        String externalKey = request.getParameter(EXTERNAL_LOGIN_KEY_ATTR);
        if (externalKey == null) return "success";

        GenericValue userLogin = null;
//        = CloudCardLoginWorker.externalLoginKeys.get(externalKey);
        if (userLogin != null) {
           /* //to check it's the right tenant
            //in case username and password are the same in different tenants
            LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
            Delegator delegator = (Delegator) request.getAttribute("delegator");
            String oldDelegatorName = delegator.getDelegatorName();
            ServletContext servletContext = session.getServletContext();
            if (!oldDelegatorName.equals(userLogin.getDelegator().getDelegatorName())) {
                delegator = DelegatorFactory.getDelegator(userLogin.getDelegator().getDelegatorName());
                dispatcher = ContextFilter.makeWebappDispatcher(servletContext, delegator);
                setWebContextObjects(request, response, delegator, dispatcher);
            }
            // found userLogin, do the external login...

            // if the user is already logged in and the login is different, logout the other user
            GenericValue currentUserLogin = (GenericValue) session.getAttribute("userLogin");
            if (currentUserLogin != null) {
                if (currentUserLogin.getString("userLoginId").equals(userLogin.getString("userLoginId"))) {
                    // is the same user, just carry on...
                    return "success";
                }

                // logout the current user and login the new user...
                logout(request, response);
                // ignore the return value; even if the operation failed we want to set the new UserLogin
            }

            doBasicLogin(userLogin, request);*/
        } else {
            Debug.logWarning("Could not find userLogin for external login key: " + externalKey, module);
        }
        request.setAttribute(EXTERNAL_LOGIN_KEY_ATTR, "new token");
        return "success";
    }

}
