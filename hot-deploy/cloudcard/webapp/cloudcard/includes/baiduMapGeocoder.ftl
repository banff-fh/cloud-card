<#--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->
<script type="text/javascript">
<#assign requestName><@ofbizUrl>getAssociatedGeoList</@ofbizUrl></#assign>
jQuery(document).ready( function() {

	var lng = ${longitude!(116.331398)};
	var lat = ${latitude!(39.897445)};

	var mapContainer = "EditCloudCardStore_baiduMap";
	var $address1 = jQuery("#EditCloudCardStore_address1");
	var $geoCity = jQuery("#EditCloudCardStore_geoCity");
	var $lng = jQuery("#EditCloudCardStore_longitude");
	var $lat = jQuery("#EditCloudCardStore_latitude");
	
	
	// 百度地图API功能
	var map = new BMap.Map(mapContainer);
	map.enableScrollWheelZoom(true);
	var point = new BMap.Point(lng, lat);
	map.centerAndZoom(point,12);
	// 创建地址解析器实例
	var myGeo = new BMap.Geocoder();
	
	var curMarker;
	
	$address1.change( function() {
			// 将地址解析结果显示在地图上,并调整地图视野
			myGeo.getPoint($address1.val(), function(point){
				if (point) {
					map.centerAndZoom(point, 16);
					if(curMarker){
						map.removeOverlay(curMarker);
					}
					curMarker = new BMap.Marker(point);
					map.addOverlay(curMarker);
				}else{
					showErrorAlert("\u65e0\u6cd5\u83b7\u53d6\u5750\u6807", "\u60a8\u8f93\u5165\u7684\u5730\u5740\u65e0\u6548\u0021");
				}
			}, $geoCity.find("option:selected").text());
	});
	
	// 点击选点
	map.addEventListener("click",function(e){
		var pt = e.point;
		$lng.val(pt.lng);
		$lat.val(pt.lat);
		if(curMarker){
			map.removeOverlay(curMarker);
		}
		curMarker = new BMap.Marker(pt);
		map.addOverlay(curMarker);

		myGeo.getLocation(pt, function(rs){
			var addComp = rs.addressComponents;
			showErrorAlert("address", addComp.province + ", " + addComp.city + ", " + addComp.district + ", " + addComp.street + ", " + addComp.streetNumber);
		});     

	});
	
	
	// 添加带有定位的导航控件
	var navigationControl = new BMap.NavigationControl({
	  // 靠左上角位置
	  anchor: BMAP_ANCHOR_TOP_LEFT,
	  // LARGE类型
	  type: BMAP_NAVIGATION_CONTROL_LARGE,
	  // 启用显示定位
	  enableGeolocation: true
	});
	map.addControl(navigationControl);
	// 添加定位控件
	var geolocationControl = new BMap.GeolocationControl();
	geolocationControl.addEventListener("locationSuccess", function(e){
	  // 定位成功事件
	  var address = '';
	  address += e.addressComponent.province;
	  address += e.addressComponent.city;
	  address += e.addressComponent.district;
	  address += e.addressComponent.street;
	  address += e.addressComponent.streetNumber;
	  alert("address：" + address);
	});
	geolocationControl.addEventListener("locationError",function(e){
	  // 定位失败事件
	  alert(e.message);
	});
	map.addControl(geolocationControl);
	
})
</script>
