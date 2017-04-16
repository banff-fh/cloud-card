/*
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
 */

import org.ofbiz.entity.condition.*
import org.ofbiz.entity.util.EntityUtil;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.entity.util.EntityUtilProperties;
import com.banfftech.cloudcard.util.CloudCardAdminUtil;
import org.ofbiz.base.util.Debug;
// context.inputParamEnums = delegator.findList("Enumeration", EntityCondition.makeCondition([enumTypeId : 'PROD_PRICE_IN_PARAM']), null, ['sequenceId'], null, true);
// context.condOperEnums = delegator.findList("Enumeration", EntityCondition.makeCondition([enumTypeId : 'PROD_PRICE_COND']), null, ['sequenceId'], null, true);
// context.productPriceActionTypes = delegator.findList("ProductPriceActionType", null, null, ['description'], null, true);

// String priceRuleId = request.getParameter("productPriceRuleId");

// if (!priceRuleId) {
//     priceRuleId = parameters.get("productPriceRuleId");
// }

// if (priceRuleId) {
//     productPriceRules = [];
//     productPriceRules.add(delegator.findOne("ProductPriceRule", [productPriceRuleId : priceRuleId], false));
//     productPriceConds = productPriceRules[0].getRelatedCache("ProductPriceCond");
//     productPriceConds = EntityUtil.orderBy(productPriceConds, UtilMisc.toList("productPriceCondSeqId"));
//     productPriceActions = productPriceRules[0].getRelatedCache("ProductPriceAction");
//     productPriceActions = EntityUtil.orderBy(productPriceActions, UtilMisc.toList("productPriceActionSeqId"));
    
//     productPriceCondAdd = [];
//     productPriceCondAdd.add(delegator.makeValue("ProductPriceCond"));
//     productPriceCondAdd[0].productPriceRuleId = priceRuleId;
//     productPriceCondAdd[0].inputParamEnumId = context.inputParamEnums[0].enumId;
//     productPriceCondAdd[0].operatorEnumId = context.condOperEnums[0].enumId;
    
//     productPriceActionAdd = [];
//     productPriceActionAdd.add(delegator.makeValue("ProductPriceAction"));
//     productPriceActionAdd[0].productPriceRuleId = priceRuleId;
//     productPriceActionAdd[0].productPriceActionTypeId = context.productPriceActionTypes[0].productPriceActionTypeId;
//     productPriceActionAdd[0].amount = BigDecimal.ZERO;
    
//     context.productPriceRules = productPriceRules;
//     context.productPriceConds = productPriceConds;
//     context.productPriceActions = productPriceActions;
//     context.productPriceCondAdd = productPriceCondAdd;
//     context.productPriceActionAdd = productPriceActionAdd;
    
// } else {
//     context.productPriceRules = null;
//     context.productPriceConds = null;
//     context.productPriceActions = null;    
//     context.productPriceCondsAdd = null;
//     context.productPriceActionsAdd = null;    
// }


context.baiduMapAk = EntityUtilProperties.getPropertyValue("cloudcard", "baiduMap.ak", delegator);
context.partyId = null;
def partyId = parameters?.partyId?.trim();
if(partyId && partyId.size()>0){
    context.partyId = partyId;
    // ≤È’“µÍ∆Ã–≈œ¢
    partyGroup = delegator.findOne("PartyAndGroup", [partyId : partyId], false);
    if(partyGroup){
	    context.storeName = partyGroup.groupName;
	    context.description = partyGroup.description;

	    storeOwnerIds = CloudCardAdminUtil.getStoreOwnerPartyIds(delegator, partyId);
	    Debug.logInfo("storeOwnerIds==="+storeOwnerIds,"CloudCardAdminUtil");
	    if(storeOwnerIds){
	    	storeOwnerIdOne = storeOwnerIds.get(0);
	    	storeOwner = delegator.findOne("Person", [partyId : storeOwnerIdOne], false);
	    	context.storeOwnerName = storeOwner?.firstName;

	    	storeOwnerTeleNumberList = delegator.findList("TelecomNumberAndUserLogin", EntityCondition.makeCondition([partyId : storeOwnerIdOne]), null, null, null, true);
	    	Debug.logInfo("storeOwnerTeleNumberList==="+storeOwnerTeleNumberList,"CloudCardAdminUtil");
	    	context.storeOwnerTeleNumber = storeOwnerTeleNumberList?.get(0).contactNumber;


	    	
	    }
    }



    
}