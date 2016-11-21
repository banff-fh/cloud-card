package com.banfftech.cloudcard.test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.ServiceUtil;
import org.ofbiz.service.testtools.OFBizTestCase;

import com.banfftech.cloudcard.CloudCardHelper;

/**
 * @author subenkun
 *
 */
public class CloudCardQueryServicesTest extends OFBizTestCase {
	public CloudCardQueryServicesTest(String name) {
		super(name);
	}

	public void testCloudCardQueryServicesOperations() throws GenericEntityException, GenericServiceException {
		GenericValue userLogin = delegator.findOne("UserLogin", UtilMisc.toMap("userLoginId", "subenkun"), false);
        Map<String, Object> ctx = new HashMap<String, Object>();
        ctx.put("viewIndex", 0);
        ctx.put("viewSize", 20);
        ctx.put("userLogin", userLogin);
        //测试我的卡
        Map<String, Object> resp = dispatcher.runSync("myCloudCards", ctx);
        assertTrue("Service 'myCloudCards' result success", ServiceUtil.isSuccess(resp));
        ctx.clear();
        
        //用户查询交易流水
        ctx.put("viewIndex", 0);
        ctx.put("viewSize", 20);
        ctx.put("type", "1");
        ctx.put("userLogin", userLogin);
        resp = dispatcher.runSync("getUserPayment", ctx);
        assertTrue("Service 'getUserPayment' result success", ServiceUtil.isSuccess(resp));
        ctx.clear();

        //查询信用额度
        List<String> organizationPartyIds = CloudCardHelper.getOrganizationPartyId(delegator, userLogin.getString("partyId"));
        ctx.put("userLogin", userLogin);
        ctx.put("organizationPartyId", organizationPartyIds.get(0));
        resp = dispatcher.runSync("getLimitAndPresellInfo", ctx);
        assertTrue("Service 'getLimitAndPresellInfo' result success", ServiceUtil.isSuccess(resp));
        BigDecimal presellAmount = (BigDecimal) resp.get("presellAmount");
        BigDecimal limitAmount = (BigDecimal) resp.get("limitAmount");
        BigDecimal balance = (BigDecimal) resp.get("balance");
        assertEquals("1200.00", String.valueOf(presellAmount));
        assertEquals("10000.00", String.valueOf(limitAmount));
        assertEquals("8900.00",String.valueOf(balance));
        ctx.clear();
        
        //根据二维码查询卡信息
        ctx.put("userLogin", userLogin);
        ctx.put("organizationPartyId", organizationPartyIds.get(0));
        ctx.put("cardCode", "00812134736130161527");
        
        resp = dispatcher.runSync("getCardInfoByCode", ctx);
        assertTrue("Service 'getCardInfoByCode' result success", ServiceUtil.isSuccess(resp));
        String isActivated = (String) resp.get("isActivated");
        String cardId = (String) resp.get("cardId");
        String cardName = (String) resp.get("cardName");
        String cardImg = (String) resp.get("cardImg");
        BigDecimal cardBalance = (BigDecimal) resp.get("cardBalance");
        String distributorPartyId = (String) resp.get("distributorPartyId");
        String customerPartyId = (String)  resp.get("customerPartyId");
        String ownerPartyId = (String) resp.get("ownerPartyId");
        assertEquals("Y", isActivated);
        assertEquals("10000", cardId);
        assertEquals("咖啡店储值卡", cardName);
        assertEquals("", cardImg);
        assertEquals("1200.00", String.valueOf(cardBalance));
        assertEquals("10000", distributorPartyId);
        assertEquals("10010", customerPartyId);
        assertEquals("10010", ownerPartyId);
        ctx.clear();
        
    }
}
