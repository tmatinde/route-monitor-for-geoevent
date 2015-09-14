package com.esri.ges.manager.plan.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.zip.GZIPInputStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicHeader;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.util.EntityUtils;

import com.esri.ges.core.component.RunningException;
import com.esri.ges.core.geoevent.FieldException;
import com.esri.ges.core.geoevent.GeoEvent;
import com.esri.ges.core.geoevent.GeoEventDefinition;
import com.esri.ges.core.http.GeoEventHttpClientService;
import com.esri.ges.core.http.KeyValue;
import com.esri.ges.core.property.PropertyException;
import com.esri.ges.datastore.agsconnection.ArcGISServerConnection;
import com.esri.ges.manager.alerts.AlertsManager;
import com.esri.ges.manager.autoarrivaldeparture.AutoArrivalDepartureManager;
import com.esri.ges.manager.datastore.agsconnection.ArcGISServerConnectionManager;
import com.esri.ges.manager.geoeventdefinition.GeoEventDefinitionManager;
import com.esri.ges.manager.messages.MessagesManager;
import com.esri.ges.manager.plan.PlanManager;
import com.esri.ges.manager.routemonitor.util.PlanStatus;
import com.esri.ges.manager.routes.Plan;
import com.esri.ges.manager.routes.Route;
import com.esri.ges.manager.routes.RouteManager;
import com.esri.ges.manager.stops.Stop;
import com.esri.ges.manager.stops.StopsManager;
import com.esri.ges.manager.stream.StreamManager;
import com.esri.ges.manager.vehicles.Vehicle;
import com.esri.ges.manager.vehicles.VehiclesManager;
import com.esri.ges.messaging.GeoEventCreator;
import com.esri.ges.messaging.Messaging;
import com.esri.ges.registry.transport.TransportProxy;
import com.esri.ges.stream.Stream;
import com.esri.ges.transport.Transport;

public class PlanManagerImpl implements PlanManager {
	final private static Log log = LogFactory.getLog(PlanManagerImpl.class);
	private final String ACTION_FIELD = "Action";
	private final String STATUS_FIELD = "Status";
	private final String PLANFOLDER_FIELD = "PlanFolder";
	private final String ACTION_CLEAR = "Clear";
	private final String ACTION_GET = "Get";
	private final String ACTION_RELOAD = "Reload";
	private final String ACTION_LOAD = "Load";

	private AutoArrivalDepartureManager autoArrivalDepartureManager;
	private StopsManager stopsManager;
	private RouteManager routeManager;
	private VehiclesManager vehiclesManager;
	private AlertsManager alertsManager;
	private GeoEventDefinitionManager geoEventDefinitionManager;
	private GeoEventCreator geoEventCreator;
	private String planGEDOwner;
	private String planCommandGEDName;
	private StreamManager streamManager;
	private String planInputName;
	private MessagesManager messagesManager;
	private GeoEventHttpClientService httpClientService;
	private ArcGISServerConnectionManager agsConnectionManager;
	private int timeOutSeconds;
	private String dispatchGroupFieldName = "DISPATCHGRP";

	

	@Override
	public GeoEventDefinition getPlanCommandGeoEventDefinition() {
		return geoEventDefinitionManager.searchGeoEventDefinition(
				planCommandGEDName, planGEDOwner);
	}

	public void clearFeatures(String agsConnectionName, String path,
			String featureService, String layer, String dispatchGroup) 
					throws Exception {
		ArcGISServerConnection agsConnection = this.getAgsConnectionManager()
				.getArcGISServerConnection(agsConnectionName);
		String url  = Val.chkStr(agsConnection.getUrl().toExternalForm());
		String tmpPath = null;
		if(Val.chkStr(path).equals("")) {
			
		}
		String rest = "rest/services/" + path + "/" + featureService + "/FeatureServer/" + layer + "/deleteFeatures";
		rest = rest.replace("//", "/");
		if(!url.endsWith("/")) {
			url += "/";
		}
		url += rest;
		log.info("Clear service " + url + ", dispatchGroup " + dispatchGroup);
		HttpConnector connector = new HttpConnector();
		Collection<KeyValue> params = new ArrayList<KeyValue>();
	    params.add( new KeyValue( "f", "json" ) );
	    String whereClause = "1=1";
	    if(!"".equals(Val.chkStr(dispatchGroup))) {
	    	whereClause = 
	    			this.dispatchGroupFieldName + "= '" + Val.chkStr(dispatchGroup) + "'"; /*+ 
	    	        this.dispatchGroupFieldName + " IS NULL or " +
	    	        this.dispatchGroupFieldName + "=''";*/
	    }
	    params.add( new KeyValue( "where", whereClause ) );
	    Iterator<KeyValue> iter = params.iterator();
        String postBody = "";
        while(iter.hasNext()) {
    	  KeyValue kv = iter.next();
    	  postBody += kv.getKey() + "=" + URLEncoder.encode(kv.getValue(), "UTF-8") + '&';
        }
	    
		String response = connector.createHttpRequest(url,
				"post",  
				"application/x-www-form-urlencoded", 
				postBody, 
				getTimeOutSeconds() * 1000);
		if(!(response.replace(" ", "")).contains("\"success\":true")) {
			throw new Exception("response = " + response + ", " +
					", url = " + url + ", postbody = " + postBody);
		}
	}

	@Override
	public GeoEvent clearPlan(GeoEvent geoEvent, String agsConnectionName,
			String path, String featureService, String stopLayer,
			String routeLayer, String vehicleLayer, String geofenceLayer,
			String alertLayer, String messagesLayer) {
		
		// HRSD changes to clear by dispatchgroup.  Incidents and geofences
		// not included in dispatchgroup clear
		
		GeoEvent newGeoEvent = (GeoEvent) geoEvent.clone(null);
		String dispatchGrp = "";
		if (newGeoEvent.getField("dispatchGroup") != null) {
			dispatchGrp = Val.chkStr(newGeoEvent.getField("dispatchGroup")
					.toString());
		}
		log.info("Clearing geovent = " + newGeoEvent);
		try {
			
			List<String> dispatchGrpRouteNames = new LinkedList<String>();
			
			if(!"".equals(dispatchGrp)) {
				log.info("Clearing with dispatchGroup = " + dispatchGrp);
				List<Stop> removeStops = new LinkedList<Stop>();
			    List<Stop> stops = stopsManager.getStops();
			    List<String> stopNames = new LinkedList<String>();
			    for(Stop stop: stops) {
			    	if(stop.getAttribute(dispatchGroupFieldName).equals(dispatchGrp)) {
			    		String routeName = stop.getRouteName();
			    		stopNames.add(stop.getName());
			    		if(dispatchGrpRouteNames.contains(routeName)) {
			    			dispatchGrpRouteNames.add(routeName);
			    			
			    		}
			    		removeStops.add(stop);
			    	}
			    }
			    log.info("dispatchGroup = " + dispatchGrp + 
			    		", dispatchGroupKeyNames" + dispatchGrpRouteNames);
			    log.info("Removing memory stosp " + removeStops);
			    stopsManager.removeAllStops(stopNames);
			} else {
				stopsManager.removeAllStops();;
				log.info("Cleared stops from memory");
			}
			clearFeatures(agsConnectionName, path, featureService,
					stopLayer, dispatchGrp);
			
			// clear routes
			clearFeatures(agsConnectionName, path, featureService, routeLayer,
					dispatchGrp);
			if("".equals(dispatchGrp)) {
				routeManager.removeAllRoutes();
				log.info("Cleared routes from memeory");
			} else {
				for (String routeName : dispatchGrpRouteNames) {
					Route route = routeManager.getRouteByName(routeName);
					if (route == null
							|| !routeManager.getRoutes().contains(route)) {

						log.error("Did not find route in memory to delete routeName = "
								+ routeName
								+ "dispatchGroup = "
								+ dispatchGrp
								+ ", Route = " + route);
					}
				}
				log.info("Removing routes " + dispatchGrpRouteNames);
				routeManager.removeAllRoutes(dispatchGrpRouteNames);
			}
			
			// clear vehicles
			clearFeatures(agsConnectionName, path, featureService,
					vehicleLayer, dispatchGrp);
			if("".equals(dispatchGrp)) {
				
				vehiclesManager.removeAllVehicles();
				log.info("Cleared vehicles from memory");
			} else {
				
				for (String routeName : dispatchGrpRouteNames) {
					Vehicle v = vehiclesManager.getVehicleByName(routeName);
					if (v == null || ! vehiclesManager.getVehicles().contains(v)) {
		
						log.error("Did not find vehicle in memory to delete vehicleName = "
								+ routeName + "dispatchGroup = " + 
								dispatchGrp + ", Vehicle " + v);
					}
				}
				vehiclesManager.removeAllVehicles(dispatchGrpRouteNames);
			}
			
		
			clearFeatures(agsConnectionName, path, featureService,
					geofenceLayer, null);

		
			clearFeatures(agsConnectionName, path, featureService, alertLayer,
					dispatchGrp);
			
			clearFeatures(agsConnectionName, path, featureService,
					messagesLayer,
					dispatchGrp);


			autoArrivalDepartureManager.clearIncidents();

			newGeoEvent
					.setField(STATUS_FIELD, PlanStatus.Successful.toString());

		} catch (Throwable e) {
			log.error("Error while clearing routes" , e);
			try {
				newGeoEvent
						.setField(STATUS_FIELD, PlanStatus.Failed.toString());
			} catch (FieldException e1) {
				log.error(e1.getStackTrace());
			}
			try {
				newGeoEvent.setField("DESCRIPTION", e.getMessage());
			} catch (FieldException e1) {
				log.error(e1.getStackTrace());
			}
		}
		return newGeoEvent;
	}

	@Override
	public GeoEvent getPlan() {
		// List<Vehicle> vehicles = vehiclesManager.getVehicles();
		List<Stop> stops = stopsManager.getStops();
		List<Route> routes = new ArrayList<Route>(routeManager.getRoutes());
		Plan plan = new Plan();
		plan.setRoutes(routes);
		plan.setStops(stops);
		GeoEvent newGeoEvent = null;
		try {
			newGeoEvent = routeManager.createPlanGeoEvent(plan, false,
					PlanStatus.Successful, "");
		} catch (Exception ex) {
			log.error(ex);
			newGeoEvent = routeManager.createPlanGeoEvent(null, false,
					PlanStatus.Failed, ex.getMessage());
		}
		return newGeoEvent;
	}

	@Override
	public GeoEvent reloadPlan(String agsConnectionName, String path,
			String featureService, String stopLayer, String routeLayer,
			String vehicleLayer, String alertLayer) {
		vehiclesManager.reloadVehicles(agsConnectionName, path, featureService,
				vehicleLayer);
		List<Stop> stops = stopsManager.reloadStops(agsConnectionName, path,
				featureService, stopLayer);
		List<Route> routes = routeManager.reloadRoutes(agsConnectionName, path,
				featureService, routeLayer);
		Plan plan = new Plan();
		plan.setRoutes(routes);
		plan.setStops(stops);
		GeoEvent planGE = null;
		try {
			if (stops != null || routes != null)
				planGE = routeManager.createPlanGeoEvent(plan, false,
						PlanStatus.Successful, "");
		} catch (Exception e) {
			log.error(e);
			planGE = routeManager.createPlanGeoEvent(null, false,
					PlanStatus.Failed, e.getMessage());
		}
		resetVehicleNextSequenceNumber();
		return planGE;
	}

	@Override
	public GeoEvent loadPlan(GeoEvent geoEvent) {
		String planFolder = geoEvent.getField(PLANFOLDER_FIELD).toString();
		GeoEvent newGeoEvent = (GeoEvent) geoEvent.clone(null);
		Stream input;

		try {
			input = findInput(planInputName);
			notifyInputs(input, planFolder);
			newGeoEvent
					.setField(STATUS_FIELD, PlanStatus.Successful.toString());
		} catch (PropertyException e) {
			log.error(e.getStackTrace());
			try {
				newGeoEvent
						.setField(STATUS_FIELD, PlanStatus.Failed.toString());
			} catch (FieldException e1) {
				log.error(e1.getStackTrace());
			}
		} catch (RunningException e) {
			log.error(e.getStackTrace());
			try {
				newGeoEvent
						.setField(STATUS_FIELD, PlanStatus.Failed.toString());
			} catch (FieldException e1) {
				log.error(e1.getStackTrace());
			}
		} catch (FieldException e) {
			log.error(e.getStackTrace());
		}

		resetVehicleNextSequenceNumber();
		return newGeoEvent;
	}

	private void resetVehicleNextSequenceNumber() {
		List<Vehicle> vehicles = vehiclesManager.getVehicles();
		for (Vehicle v : vehicles) {
			v.setNextStopSequenceNumber(stopsManager
					.getSequenceNumberForNextAssignedStop(v.getVehicleName()));
		}
	}

	private void notifyInputs(Stream input, String planFolder)
			throws PropertyException, RunningException {
		TransportProxy tp = input.getTransportProxy();
		Transport t = tp.getProxiedTransport();

		input.stop();
		t.setProperty("inputDirectory", planFolder);
		input.start();

	}

	private Stream findInput(String name) {
		Stream input = null;
		Collection<Stream> inputs = streamManager.getInboundStreams();
		for (Stream stream : inputs) {
			if (stream.getName().equals(name)) {
				input = stream;
				input.getAdapterProxy();
			}
		}
		return input;
	}

	@Override
	public String getPlanCommandActionField() {
		return ACTION_FIELD;
	}

	@Override
	public String getPlanCommandActionClear() {
		return ACTION_CLEAR;
	}

	@Override
	public String getPlanCommandActionGet() {
		return ACTION_GET;
	}

	@Override
	public String getPlanCommandActionLoad() {
		return ACTION_LOAD;
	}

	@Override
	public String getPlanCommandActionReload() {
		return ACTION_RELOAD;
	}

	public void setMessagesManager(MessagesManager messagesManager) {
		this.messagesManager = messagesManager;
	}

	public void setStopsManager(StopsManager stopsManager) {
		this.stopsManager = stopsManager;
	}

	public void setRouteManager(RouteManager routeManager) {
		this.routeManager = routeManager;
	}

	public void setVehiclesManager(VehiclesManager vehiclesManager) {
		this.vehiclesManager = vehiclesManager;
	}

	public void setPlanGEDOwner(String planGEDOwner) {
		this.planGEDOwner = planGEDOwner;
	}

	public void setPlanCommandGEDName(String planCommandGEDName) {
		this.planCommandGEDName = planCommandGEDName;
	}

	public void setMessaging(Messaging messaging) {
		this.geoEventCreator = messaging.createGeoEventCreator();
		this.geoEventDefinitionManager = geoEventCreator
				.getGeoEventDefinitionManager();
	}

	public void setStreamManager(StreamManager streamManager) {
		this.streamManager = streamManager;
	}

	public void setPlanInputName(String planInputName) {
		this.planInputName = planInputName;
	}

	public void setAlertsManager(AlertsManager alertsManager) {
		this.alertsManager = alertsManager;
	}

	public void setAutoArrivalDepartureManager(
			AutoArrivalDepartureManager autoArrivalDepartureManager) {
		this.autoArrivalDepartureManager = autoArrivalDepartureManager;
	}

	public GeoEventHttpClientService getHttpClientService() {
		return httpClientService;
	}

	public void setHttpClientService(GeoEventHttpClientService httpClientService) {
		this.httpClientService = httpClientService;
	}

	public ArcGISServerConnectionManager getAgsConnectionManager() {
		return agsConnectionManager;
	}

	public void setAgsConnectionManager(
			ArcGISServerConnectionManager agsConnectionManager) {
		this.agsConnectionManager = agsConnectionManager;
	}
	
	public int getTimeOutSeconds() {
		if(timeOutSeconds <= 0) {
			timeOutSeconds = 180;
		}
		return timeOutSeconds;
	}

	public void setTimeOutSeconds(int timeOut) {
		this.timeOutSeconds = timeOut;
	}

	public String getDispatchGroupFieldName() {
		return dispatchGroupFieldName;
	}

	public void setDispatchGroupFieldName(String dispatchGroupFieldName) {
		this.dispatchGroupFieldName = dispatchGroupFieldName;
	}
	
	

}
