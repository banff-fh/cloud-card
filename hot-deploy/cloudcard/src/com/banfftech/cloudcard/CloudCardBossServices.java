package com.banfftech.cloudcard;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilGenerics;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.util.EntityUtil;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

import com.banfftech.cloudcard.constant.CloudCardConstant;

import javolution.util.FastList;

/**
 * 库胖卡商家相关的服务
 * @author ChenYu
 *
 */
public class CloudCardBossServices {
	
	public static final String module = CloudCardBossServices.class.getName();
	
	/**
	 * 商户开通申请
	 * 		通过搜集的信息创建一个SurveyResponse 与 SurveyResponseAnswer
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> bizCreateApply(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		//GenericValue userLogin = (GenericValue) context.get("userLogin");
		
		String storeName = (String)context.get("storeName"); // 店名
		String storeAddress = (String)context.get("storeAddress"); // 商铺详细地址
		String teleNumber = (String)context.get("teleNumber"); // 店主电话
		String personName = (String)context.get("personName"); // 店主姓名
		String description = (String)context.get("description"); // 店铺描述
		String longitude = (String)context.get("longitude"); // 经度
		String latitude = (String)context.get("latitude"); // 纬度
		
		String surveyId = "SURV_CC_STORE_INFO"; //用于 收集库胖卡商家的 “调查（Survey）实体”
		String statusId = "SRS_CREATED";


		// 必要的参数检验逻辑
		// 比如： 传入的电话号码 已经有了 有效的申请，则忽略本次
		//     （有效的状态为 创建 或 已经通过 的申请， 而 拒绝 和 取消 状态的申请是可以重新申请的）
		try {
			GenericValue  oldAnswer = EntityUtil.getFirst(delegator.findByAndCache("SurveyResponseAnswer", 
					UtilMisc.toMap("surveyQuestionId", "SQ_CC_S_OWNER_TEL", "textResponse", teleNumber), 
					UtilMisc.toList("-answeredDate")));
			if(null != oldAnswer){
				GenericValue response = oldAnswer.getRelatedOne("SurveyResponse");
				if(null != response){
					String responseStatus = response.getString("statusId");
					if("SRS_CREATED".equals(responseStatus) || "SRS_ACCEPTED".equals(responseStatus) ){
						Debug.logError("teleNumber[" + teleNumber + "] has been used to apply for a shop, status[" + responseStatus + "]", module);
//						return ServiceUtil.returnError("此店主电话已经进行了开店申请"); 
						return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardRepeatedApplyForCreateStore", locale)); 
					}
				}
			}
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}



		// 创建“调查回答”，用于记录开店申请者提交的信息
		Map<String, Object> createSurveyResponseOutMap;
		try {
			createSurveyResponseOutMap = dispatcher.runSync("createSurveyResponse",
					UtilMisc.toMap("locale",locale,
							"surveyId", surveyId, 
							"statusId", statusId,
							"answers", UtilMisc.toMap("SQ_CC_STORE_NAME", storeName,
									"SQ_CC_STORE_ADDR", storeAddress,
									"SQ_CC_S_OWNER_TEL", teleNumber,
									"SQ_CC_S_OWNER_NAME", personName,
									"SQ_CC_STORE_DESC", description,
									"SQ_CC_S_LONGITUDE", longitude,
									"SQ_CC_S_LATITUDE", latitude
									)
							));
		} catch (GenericServiceException e1) {
			Debug.logError(e1.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(createSurveyResponseOutMap) || ServiceUtil.isFailure(createSurveyResponseOutMap)) {
			return createSurveyResponseOutMap;
		}


		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("responseId", createSurveyResponseOutMap.get("surveyResponseId"));
		return result;
	}

	/**
	 * 商户创建圈子
	 * 		创建个圈子，同时发送圈子加入邀请，如果有传入teleNumberList的话
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> bizCreateGroup(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		GenericValue userLogin = (GenericValue) context.get("userLogin");

		String organizationPartyId = (String)context.get("organizationPartyId"); //店家partyId
		String groupName = (String)context.get("groupName"); // 圈子名

		// system用户
		GenericValue systemUser = null;
		try {
			systemUser = delegator.findByPrimaryKey("UserLogin", UtilMisc.toMap("userLoginId", "system"));
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		// 数据权限检查
		if( !CloudCardHelper.isManager(delegator, userLogin.getString("partyId"), organizationPartyId)){
			Debug.logError("partyId: " + userLogin.getString("partyId") + " 不是商户：" + organizationPartyId + "的管理人员，不能创建圈子", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardUserLoginIsNotManager", locale));
		}
		
		// organizationPartyId 合法性
		GenericValue partyGroup = null;
		try {
			partyGroup = delegator.findByPrimaryKey("PartyGroup", UtilMisc.toMap("partyId", organizationPartyId));
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		if(null == partyGroup ){
			Debug.logWarning("商户：" + organizationPartyId + "不存在", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, 
					"CloudCardOrganizationPartyNotFound", UtilMisc.toMap("organizationPartyId", organizationPartyId), locale));
		}

		//如果没有传入“圈子名”这个参数， 则使用商家名+圈子 作为名称
		if(UtilValidate.isEmpty(groupName)){
			groupName = partyGroup.getString("groupName") + "的圈子"; 
		}

		// 检查是否已经 建立/加入 圈子
		List<EntityCondition> condList = FastList.newInstance();
		condList.add(EntityCondition.makeCondition("partyIdTo", organizationPartyId));
		condList.add(EntityCondition.makeCondition("roleTypeIdFrom", CloudCardConstant.STORE_GROUP_ROLE_TYPE_ID));
		condList.add(EntityCondition.makeCondition("partyRelationshipTypeId",  CloudCardConstant.STORE_GROUP_PARTY_RELATION_SHIP_TYPE_ID));
		condList.add(EntityUtil.getFilterByDateExpr());
		List<GenericValue> partyRelationships = null;
		try {
			partyRelationships = delegator.findList("PartyRelationship", EntityCondition.makeCondition(condList), null, null, null, false);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), "Problem finding PartyRelationships. ", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		if (UtilValidate.isNotEmpty(partyRelationships)) {
			// 已经在圈子里，不能创建新的圈子
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardIsInGroupCanNotCreateAnother", locale));
		}


		// 创建 “圈子”以及 圈子与圈主关系
		Map<String, Object> createPartyGroupOutMap;
		String groupId;
		try {
			// 创建 “圈子”
			createPartyGroupOutMap = dispatcher.runSync("createPartyGroup",
					UtilMisc.toMap("locale",locale, "userLogin", systemUser, "groupName", groupName));
			if (ServiceUtil.isError(createPartyGroupOutMap) || ServiceUtil.isFailure(createPartyGroupOutMap)) {
				return createPartyGroupOutMap;
			}

			groupId = (String) createPartyGroupOutMap.get("partyId");

			// 创建 圈子角色
			Map<String, Object> createPartyRoleOut = dispatcher.runSync("createPartyRole",
					UtilMisc.toMap("locale",locale, "userLogin", systemUser, "partyId", groupId, "roleTypeId", CloudCardConstant.STORE_GROUP_ROLE_TYPE_ID));
			if(ServiceUtil.isError(createPartyRoleOut)|| ServiceUtil.isFailure(createPartyRoleOut)){
				return createPartyRoleOut;
			}

			// 确保 店家 具有 OWNER 角色
			Map<String, Object> ensurePartyRoleOut = dispatcher.runSync("ensurePartyRole", 
					UtilMisc.toMap("locale",locale, "userLogin", systemUser, "partyId", organizationPartyId, "roleTypeId",  CloudCardConstant.STORE_GROUP_PARTNER_ROLE_TYPE_ID));
			if(ServiceUtil.isError(ensurePartyRoleOut)|| ServiceUtil.isFailure(ensurePartyRoleOut)){
				return ensurePartyRoleOut;
			}

			// 创建 店家 与 圈子 的圈主关系
			Map<String, Object> relationOutMap = dispatcher.runSync("createPartyRelationship", 
					UtilMisc.toMap("locale",locale, "userLogin", systemUser,
							"partyIdFrom", groupId,
							"partyIdTo", organizationPartyId,
							"roleTypeIdFrom", CloudCardConstant.STORE_GROUP_ROLE_TYPE_ID,
							"roleTypeIdTo",  CloudCardConstant.STORE_GROUP_PARTNER_ROLE_TYPE_ID,
							"partyRelationshipTypeId",  CloudCardConstant.STORE_GROUP_PARTY_RELATION_SHIP_TYPE_ID
					));
			if (ServiceUtil.isError(relationOutMap) || ServiceUtil.isFailure(relationOutMap)) {
				return relationOutMap;
			}

		} catch (GenericServiceException e1) {
			Debug.logError(e1.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}


		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("groupId", groupId);
		result.put("rejectList", UtilMisc.toList(""));
		return result;
	}


	/**
	 * 向某些店主发起邀请，邀请其加入某个圈子
	 * 
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> bizSentGroupInvitation(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		GenericValue userLogin = (GenericValue) context.get("userLogin");

		String organizationPartyId = (String)context.get("organizationPartyId"); //店家partyId
		String groupId = (String)context.get("groupId"); // 圈子Id
		List<String> teleNumberList = UtilGenerics.checkList(context.get("teleNumberList")) ; // 需要邀请的店主电话

		// 数据权限检查
		if( !CloudCardHelper.isManager(delegator, userLogin.getString("partyId"), organizationPartyId)){
			Debug.logError("partyId: " + userLogin.getString("partyId") + " 不是商户：" + organizationPartyId + "的管理人员，不能创建圈子", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardUserLoginIsNotManager", locale));
		}
		
		// 
		
		List<Map<String, Object>> rejectList = FastList.newInstance();

		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("groupId", "");
		result.put("rejectList", UtilMisc.toList(""));
		return result;
	}


	/**
	 * 我的圈子
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> bizMyGroup(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		GenericValue userLogin = (GenericValue) context.get("userLogin");
		
		String organizationPartyId = (String)context.get("organizationPartyId"); //店家partyId
//		Integer viewIndex = (Integer) context.get("viewIndex");
//		Integer viewSize = (Integer) context.get("viewSize");
		
		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();

		// 数据权限检查
		if( !CloudCardHelper.isManager(delegator, userLogin.getString("partyId"), organizationPartyId)){
			Debug.logError("partyId: " + userLogin.getString("partyId") + " 不是商户：" + organizationPartyId + "的管理人员，不能查看圈子信息", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardUserLoginIsNotManager", locale));
		}


		// 查找圈子
		GenericValue partyRelationship = null;
		GenericValue storeGroup = null;
		try {
			partyRelationship = CloudCardHelper.getGroupRelationShipByStoreId(delegator, organizationPartyId, true);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), "Problem finding PartyRelationships. ", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		if (UtilValidate.isNotEmpty(partyRelationship)) {
			// 在圈子里，
			result.put("", "");
		}

	
		result.put("groupId", "");
		result.put("rejectList", UtilMisc.toList(""));
		return result;
	}
	
	
}
