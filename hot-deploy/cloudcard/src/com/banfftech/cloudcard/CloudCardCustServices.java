package com.banfftech.cloudcard;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.banfftech.cloudcard.constant.CloudCardConstant;
import com.banfftech.cloudcard.lbs.BaiduLBSUtil;

import javolution.util.FastList;
import javolution.util.FastMap;

public class CloudCardCustServices {
	public static final String module = CloudCardCustServices.class.getName();

	/**
	 * 附近的店铺
	 * 
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> userStoreListLBS(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");

		String longitude = (String) context.get("longitude");
		String latitude = (String) context.get("latitude");

		String ak = EntityUtilProperties.getPropertyValue("cloudcard", "baiduMap.ak", delegator);
		String getTableId = EntityUtilProperties.getPropertyValue("cloudcard", "baiduMap.getTableId", delegator);
		String radius = EntityUtilProperties.getPropertyValue("cloudcard", "baiduMap.radius", delegator);
		String CoordType = EntityUtilProperties.getPropertyValue("cloudcard", "baiduMap.CoordType", delegator);
		String q = EntityUtilProperties.getPropertyValue("cloudcard", "baiduMap.q", delegator);

		Map<String, Object> params = FastMap.newInstance();
		params.put("ak", ak);
		params.put("geotable_id", getTableId);
		params.put("q", q);
		params.put("Coord_type", CoordType);
		params.put("location", longitude + "," + latitude);
		params.put("radius", radius);
		
		List<Map<String,Object>> storeList = FastList.newInstance();
		JSONObject lbsResult = JSONObject.parseObject(BaiduLBSUtil.nearby(params));
		if(!"0".equalsIgnoreCase(lbsResult.get("total").toString())){
			JSONArray jsonArray = JSONObject.parseArray(lbsResult.get("contents").toString());
			for(int i = 0 ;i<jsonArray.size();i++){
				Map<String, Object> storeMap = FastMap.newInstance();
				storeMap.put("storeName",jsonArray.getJSONObject(i).getObject("storeName",String.class));
				storeMap.put("address",jsonArray.getJSONObject(i).getObject("address",String.class));
				storeMap.put("telNum",jsonArray.getJSONObject(i).getObject("telNum",String.class));
				storeMap.put("storeId",jsonArray.getJSONObject(i).getObject("storeId",String.class) );
				storeMap.put("isGroupOwner",jsonArray.getJSONObject(i).getObject("isGroupOwner",String.class) );
				storeMap.put("distance",jsonArray.getJSONObject(i).getObject("distance",String.class) );
				storeMap.put("location",jsonArray.getJSONObject(i).getObject("location",String.class) );
				storeList.add(storeMap);
			}
		}
		
		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("listSize", lbsResult.get("total").toString());
		result.put("storeList", storeList);
		return result;
	}
	
	/**
	 * 查看店铺简要信息
	 * 
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> userGetStoreInfo(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		
		String storeId = (String) context.get("storeId");
		String storeName = null;
		String storeImg = null;
		String storeAddress = null;
		String storeTeleNumber = null;
		String longitude = null;
		String latitude = null;
		GenericValue partyGroup = null;
		try {
			partyGroup = delegator.findByPrimaryKey("PartyGroup", UtilMisc.toMap("partyId", storeId));
		} catch (GenericEntityException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		if (UtilValidate.isNotEmpty( partyGroup)) {
			storeName = (String) partyGroup.get("groupName");
		}
		
		storeImg = EntityUtilProperties.getPropertyValue("cloudcard","cardImg." + storeId,delegator);


		List<GenericValue> PartyAndContactMechs = FastList.newInstance();
		try {
			PartyAndContactMechs = delegator.findList("PartyAndContactMech", EntityCondition.makeCondition("partyId", storeId), null, null, null, true);
		} catch (GenericEntityException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		if (UtilValidate.isNotEmpty(PartyAndContactMechs)) {
			for (GenericValue partyAndContactMech : PartyAndContactMechs) {
			     String cmType=partyAndContactMech.getString("contactMechTypeId");
				if("POSTAL_ADDRESS".equals(cmType)){
					storeAddress = (String) partyAndContactMech.get("paAddress1");
				}else if(("TELECOM_NUMBER".equals(cmType))){
					storeTeleNumber = (String) partyAndContactMech.get("tnContactNumber");
				}
			}
		}
		
		
		List<GenericValue> partyAndGeoPoints = FastList.newInstance();
		try {
			partyAndGeoPoints = delegator.findList("PartyAndGeoPoint", EntityCondition.makeCondition("partyId", storeId), null, null, null, true);
		} catch (GenericEntityException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		if(UtilValidate.isNotEmpty(partyAndGeoPoints)){
			longitude = partyAndGeoPoints.get(0).getString("longitude");
			latitude = partyAndGeoPoints.get(0).getString("latitude");
		}
		
		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("storeId", storeId);
		result.put("storeName", storeName);
		result.put("storeImg", storeImg);
		result.put("storeAddress", storeAddress);
		result.put("storeTeleNumber", storeTeleNumber);
		result.put("longitude", longitude);
		result.put("latitude", latitude);

		return result;
	}
	
	/**
	 * 查看查看圈子信息
	 * 
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> userGetGroupInfo(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		
		String storeId = (String) context.get("storeId");
		//获取圈子Id
		String partyGroupId = null;
		try {
			partyGroupId = CloudCardHelper.getGroupIdByStoreId(delegator,storeId,false);
		} catch (GenericEntityException e1) {
			e1.printStackTrace();
		}
		
		//获取圈名
		String groupName = null;
		GenericValue pg = null;
		try {
			pg = delegator.findByPrimaryKey("PartyGroup", UtilMisc.toMap("partyId", partyGroupId));
		} catch (GenericEntityException e1) {
			e1.printStackTrace();
		}
		
		if(UtilValidate.isNotEmpty(pg)){
			groupName = pg.getString("groupName");
		}
		
		
		List<String> storeIdList = FastList.newInstance();
		try {
			storeIdList = CloudCardHelper.getStoreGroupPartnerListByStoreId(delegator,storeId,true);
		} catch (GenericEntityException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		List<Map<String,Object>> storeList = FastList.newInstance();
		if(UtilValidate.isNotEmpty(storeIdList)){
			for(String storeRs: storeIdList){
				String storeName = null;
				String storeImg = null;
				String storeAddress = null;
				String storeTeleNumber = null;
				String longitude = null;
				String latitude = null;
				String isGroupOwner = null;
				
				Boolean isStoreGroupOwner = null;
				try {
					isStoreGroupOwner = CloudCardHelper.isStoreGroupOwner(delegator,storeRs,false);
				} catch (GenericEntityException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
				if(isStoreGroupOwner){
					isGroupOwner = "Y";
				}else{
					isGroupOwner = "N";
				}
				
				GenericValue partyGroup = null;
				try {
					partyGroup = delegator.findByPrimaryKey("PartyGroup", UtilMisc.toMap("partyId", storeRs));
				} catch (GenericEntityException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}

				if (UtilValidate.isNotEmpty( partyGroup)) {
					storeName = (String) partyGroup.get("groupName");
				}
				
				storeImg = EntityUtilProperties.getPropertyValue("cloudcard","cardImg." + storeRs,delegator);


				List<GenericValue> PartyAndContactMechs = FastList.newInstance();
				try {
					PartyAndContactMechs = delegator.findList("PartyAndContactMech", EntityCondition.makeCondition("partyId", storeRs), null, null, null, true);
				} catch (GenericEntityException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}

				if (UtilValidate.isNotEmpty(PartyAndContactMechs)) {
					for (GenericValue partyAndContactMech : PartyAndContactMechs) {
						if(partyAndContactMech.get("contactMechTypeId").toString().equals("POSTAL_ADDRESS")){
							storeAddress = (String) partyAndContactMech.get("paAddress1");
						}else if(partyAndContactMech.get("contactMechTypeId").toString().equals("TELECOM_NUMBER")){
							storeTeleNumber = (String) partyAndContactMech.get("tnContactNumber");
						}
					}
				}
				
				
				List<GenericValue> partyAndGeoPoints = FastList.newInstance();
				try {
					partyAndGeoPoints = delegator.findList("PartyAndGeoPoint", EntityCondition.makeCondition("partyId", storeRs), null, null, null, true);
				} catch (GenericEntityException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
				if(UtilValidate.isNotEmpty(partyAndGeoPoints)){
					longitude = partyAndGeoPoints.get(0).getString("longitude");
					latitude = partyAndGeoPoints.get(0).getString("latitude");
				}
				
				Map<String,Object> storeMap = FastMap.newInstance();
				storeMap.put("storeId", storeId);
				storeMap.put("storeName", storeName);
				storeMap.put("storeImg", storeImg);
				storeMap.put("storeAddress", storeAddress);
				storeMap.put("storeTeleNumber", storeTeleNumber);
				storeMap.put("isGroupOwner", isGroupOwner);
				storeMap.put("longitude", longitude);
				storeMap.put("latitude", latitude);
				storeList.add(storeMap);
			}
		}
		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("storeId", storeId);
		result.put("groupName", groupName);
		result.put("storeList", storeList);
		return result;
		
	}
	
	
	/**
	 * 获取支付的卡信息
	 * 
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> getPaymentCard(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		
		GenericValue userLogin = (GenericValue) context.get("userLogin");
		String partyId = (String) userLogin.get("partyId");
		String storeId = (String) context.get("storeId");
		
		//查找用户在本店购买的卡
		Map<String,Object> cardMap = FastMap.newInstance();
		cardMap.put("userLogin", userLogin);
		Map<String,Object> cloudCardMap = FastMap.newInstance();
		List<Object> cloudCardList = null;
		try {
			cloudCardMap = dispatcher.runSync("myCloudCards", cardMap);
		} catch (GenericServiceException e) {
			 Debug.logError(e.getMessage(), module);
	         return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		
		if(UtilValidate.isNotEmpty(cloudCardMap)){
			if(UtilValidate.isNotEmpty(cloudCardList)){
				cloudCardList.add(cloudCardMap.get("cloudCardList").toString());
			}else{
				cloudCardList = (List<Object>) cloudCardMap.get("cloudCardList");
			}
		}
		
		//查找用户在圈主购买的卡
		String groupId = null;
		try {
			groupId = CloudCardHelper.getGroupIdByStoreId(delegator,storeId,false);
		} catch (GenericEntityException e1) {
			 Debug.logError(e1.getMessage(), module);
	         return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		
		List<GenericValue> partyRelationshipList = FastList.newInstance();
		try {
			partyRelationshipList = delegator.findByAnd("PartyRelationship", UtilMisc.toMap("partyIdFrom", groupId, "roleTypeIdTo",  CloudCardConstant.STORE_GROUP_OWNER_ROLE_TYPE_ID));
		} catch (GenericEntityException e1) {
		     Debug.logError(e1.getMessage(), module);
	         return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		
		//如果自己是圈主，忽略此部
		if(!storeId.equals(partyRelationshipList.get(0).get("partyIdTo"))){
			if(UtilValidate.isNotEmpty(partyRelationshipList)){
				cardMap.put("storeId", partyRelationshipList.get(0).get("partyIdTo"));
			}
			try {
				cloudCardMap = dispatcher.runSync("myCloudCards", cardMap);
			} catch (GenericServiceException e) {
				 Debug.logError(e.getMessage(), module);
		         return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
			}
			
			if(UtilValidate.isNotEmpty(cloudCardMap)){
				if(UtilValidate.isNotEmpty(cloudCardList)){
					cloudCardList.add(cloudCardMap.get("cloudCardList").toString());
				}else{
					cloudCardList = (List<Object>) cloudCardMap.get("cloudCardList");
				}
			}
		}
		
		
		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("storeId", storeId);
		result.put("cloudCardList", cloudCardList);
		return result;
	}
	
	/**
	 * C端扫码获取商户信息和卡列表
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> userScanCodeGetCardAndStoreInfo(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dctx.getDelegator();
		GenericValue userLogin = (GenericValue) context.get("userLogin");
		Locale locale = (Locale) context.get("locale");
		//获取店铺二维码
		String qrCode = (String) context.get("qrCode");
		GenericValue partyGroup = null;
		try {
			partyGroup = CloudCardHelper.getPartyGroupByQRcode(qrCode, delegator);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		if(null == partyGroup){
			Debug.logWarning("商户qrCode:" + qrCode + "不存在", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardOrganizationPartyNotFound", locale));
		}

		String storeId = (String) partyGroup.getString("partyId");
		String groupName = (String) partyGroup.getString("groupName");

		Map<String,Object> cardMap = FastMap.newInstance();
		cardMap.put("userLogin", userLogin);
		cardMap.put("storeId", storeId);
		Map<String,Object> cloudCardMap = FastMap.newInstance();
		try {
			cloudCardMap = dispatcher.runSync("getPaymentCard", cardMap);
		} catch (GenericServiceException e) {
			 Debug.logError(e.getMessage(), module);
	         return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		
		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("qrCode", qrCode);
		result.put("storeId", storeId);
		result.put("storeName", groupName);
		result.put("cloudCardList", cloudCardMap.get("cloudCardList"));
		return result;
	}
	
	/**
	 * C端选卡获取商户信息
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> getStoreInfoBycardCode(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dctx.getDelegator();
		GenericValue userLogin = (GenericValue) context.get("userLogin");
		Locale locale = (Locale) context.get("locale");
		
		String cardCode = (String) context.get("cardCode");
		String storeId = null;
		String groupName = null;
		
		GenericValue encryptedGiftCard = delegator.makeValue("FinAccount", UtilMisc.toMap("finAccountCode", cardCode));
		try {
			delegator.encryptFields(encryptedGiftCard);
		} catch (GenericEntityException e1) {
			Debug.logError(e1.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError,
					"CloudCardInternalServiceError", locale));
		}
		
		List<GenericValue> cloudCardInfos = null;
		try {
			cloudCardInfos = delegator.findList("CloudCardInfo", EntityCondition.makeCondition("finAccountCode", encryptedGiftCard.getString("finAccountCode")), null, null, null, false);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
	        return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		
		if(UtilValidate.isNotEmpty(cloudCardInfos)){
			storeId = cloudCardInfos.get(0).getString("distributorPartyId");
			groupName =  cloudCardInfos.get(0).getString("distributorPartyName");
		}
		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("cardCode", cardCode);
		result.put("storeId", storeId);
		result.put("storeName", groupName);
		return result;
	}
	
	/**
	 * C端购卡
	 * 
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> purchaseCard(DispatchContext dctx, Map<String, Object> context) {
		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		return result;
	}
}
