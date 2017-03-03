package com.banfftech.cloudcard;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilDateTime;
import org.ofbiz.base.util.UtilFormatOut;
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
import com.auth0.jwt.JWTSigner;
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

        double longitude = Double.parseDouble(UtilFormatOut.checkNull((String) context.get("longitude"), "0.00"));
        double latitude = Double.parseDouble(UtilFormatOut.checkNull((String) context.get("latitude"), "0.00"));
		double exp = 10e-10;
        // 经度最大是180° 最小是-180° 纬度最大是90° 最小是-90°
        if (Math.abs(longitude) - 180.00 > exp || Math.abs(latitude) - 90.00 > exp) {
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardAbnormalPositioning", locale));
        }

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
		result.put("longitude", longitude);
		result.put("latitude",latitude);
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

		String storeId = partyGroup.getString("partyId");
		String groupName = partyGroup.getString("groupName");

		Map<String,Object> cardMap = FastMap.newInstance();
		cardMap.put("userLogin", userLogin);
		cardMap.put("storeId", storeId);
		Map<String,Object> cloudCardMap = FastMap.newInstance();
		try {
			cloudCardMap = dispatcher.runSync("myCloudCards", cardMap);
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
	public static Map<String, Object> getStoreInfoByCardId(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dctx.getDelegator();
		GenericValue userLogin = (GenericValue) context.get("userLogin");
		Locale locale = (Locale) context.get("locale");
		
		String cardId = (String) context.get("cardId");
		String qrCode = (String) context.get("qrCode");

		String storeId = null;
		String groupName = null;
		
		GenericValue partyGroup = null;
		try {
			partyGroup = CloudCardHelper.getPartyGroupByQRcode(qrCode, delegator);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		
		if(UtilValidate.isNotEmpty(partyGroup)){
			storeId = partyGroup.getString("partyId");
			groupName = partyGroup.getString("groupName");
		}
		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("cardId", cardId);
		result.put("qrCode", qrCode);
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
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		
		GenericValue userLogin = (GenericValue) context.get("userLogin");
		String paymentType = (String) context.get("paymentType");
		String storeId = (String) context.get("storeId");
		String totalFee = (String) context.get("totalFee");
		String paymentService = (String) context.get("paymentService");

		
		// 传入的organizationPartyId必须是一个存在的partyGroup
		GenericValue partyGroup;
		try {
			partyGroup = delegator.findByPrimaryKey("PartyGroup", UtilMisc.toMap("partyId", storeId));
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		if(null == partyGroup ){
			Debug.logWarning("商户："+storeId + "不存在", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, 
					"CloudCardOrganizationPartyNotFound", UtilMisc.toMap("organizationPartyId", storeId), locale));
		}
		
		//生成的卡号
		String newCardCode = null;
		try {
			newCardCode = CloudCardHelper.generateCloudCardCode(delegator);
			String finAccountId = delegator.getNextSeqId("FinAccount");
			Map<String, Object> finAccountMap = FastMap.newInstance();
			finAccountMap.put("finAccountId", finAccountId);
			finAccountMap.put("finAccountTypeId", "GIFTCERT_ACCOUNT");
			finAccountMap.put("statusId", "FNACT_PUBLISHED");
			finAccountMap.put("finAccountName", partyGroup.getString("groupName")+"库胖卡");
			finAccountMap.put("finAccountCode", newCardCode);
			finAccountMap.put("organizationPartyId", "Company");
			finAccountMap.put("ownerPartyId", "_NA_");
			finAccountMap.put("currencyUomId", "CNY");
			finAccountMap.put("postToGlAccountId", "213200");
			finAccountMap.put("isRefundable", "Y");
			
			//保存finaccount数据
			GenericValue finAccount = delegator.makeValue("FinAccount", finAccountMap);
			finAccount.create();
			
			//保存finaccountRole数据
			GenericValue finAccountRole = delegator.makeValue("FinAccountRole", UtilMisc.toMap( "finAccountId", finAccountId, "partyId", storeId, "roleTypeId", "DISTRIBUTOR","fromDate", UtilDateTime.nowTimestamp()));
			finAccountRole.create();
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardGenCardNumberError", locale));
		}
		
		//预下单
		Map<String,Object> uniformOrderMap = null;
		try {
			Map<String,Object> initMap = FastMap.newInstance();
			initMap.put("userLogin", userLogin);
			initMap.put("cardId", newCardCode);
			initMap.put("paymentType", paymentType);
			initMap.put("body", "买卡");
			initMap.put("totalFee", totalFee);
			initMap.put("paymentService", paymentService);

			if("aliPay".equals(paymentType)){
				initMap.put("subject", "购买库胖卡");
			}else if("wxPay".equals(paymentType)){
				initMap.put("tradeType", "APP");
			}
			uniformOrderMap = dispatcher.runSync("uniformOrder", initMap);
		} catch (GenericServiceException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("storeId", storeId);
		if("aliPay".equals(paymentType)){
			result.put("payInfo", uniformOrderMap.get("payInfo"));
		}else if("wxPay".equals(paymentType)){
			result.put("appid", uniformOrderMap.get("appid"));
			result.put("noncestr", uniformOrderMap.get("noncestr"));
			result.put("partnerid", uniformOrderMap.get("partnerid"));
			result.put("package", "Sign=WXPay");
			result.put("prepayid", uniformOrderMap.get("prepayid"));
			result.put("timestamp", uniformOrderMap.get("timestamp"));
			result.put("sign",uniformOrderMap.get("sign"));
		}
		return result;
	}
	
	/**
	 * 获取付款二维码
	 * 
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> getPaymentQRCode(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		
		GenericValue userLogin = (GenericValue) context.get("userLogin");
		String qrCode = null;
		
		long expirationTime = Long.valueOf(EntityUtilProperties.getPropertyValue("cloudcard","qrCode.expirationTime","172800L",delegator));
		String iss = EntityUtilProperties.getPropertyValue("cloudcard","qrCode.issuer",delegator);
		String tokenSecret = EntityUtilProperties.getPropertyValue("cloudcard","qrCode.secret",delegator);
		//开始时间
		final long iat = System.currentTimeMillis() / 1000L; // issued at claim 
		//Token到期时间
		final long exp = iat + expirationTime; 
		//生成Token
		final JWTSigner signer = new JWTSigner(tokenSecret);
		final HashMap<String, Object> claims = new HashMap<String, Object>();
		claims.put("iss", iss);
		claims.put("user", userLogin.get("partyId"));
		claims.put("delegatorName", delegator.getDelegatorName());
		claims.put("exp", exp);
		claims.put("iat", iat);
		qrCode = signer.sign(claims);
		
		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("qrCode", qrCode);
		return result;
	}
	
	
}
