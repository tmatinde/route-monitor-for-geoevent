package com.esri.ges.processor.autoarrivaldeparture;

import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.esri.ges.condition.ConditionService;
import com.esri.ges.condition.spatial.SpatialCondition;
import com.esri.ges.core.component.ComponentException;
import com.esri.ges.core.geoevent.GeoEvent;
import com.esri.ges.core.geoevent.GeoEventDefinition;
import com.esri.ges.core.incident.AlertType;
import com.esri.ges.core.incident.Incident;
import com.esri.ges.core.incident.IncidentType;
import com.esri.ges.core.operator.SpatialOperator;
import com.esri.ges.core.resource.Resource;
import com.esri.ges.manager.autoarrivaldeparture.AutoArrivalDepartureManager;
import com.esri.ges.manager.incident.IncidentManager;
import com.esri.ges.manager.routes.Route;
import com.esri.ges.manager.routes.RouteManager;
import com.esri.ges.manager.stops.Stop;
import com.esri.ges.manager.stops.StopStatus;
import com.esri.ges.manager.stops.StopsManager;
import com.esri.ges.manager.vehicles.Vehicle;
import com.esri.ges.manager.vehicles.VehiclesManager;
import com.esri.ges.messaging.EventDestination;
import com.esri.ges.messaging.EventProducer;
import com.esri.ges.messaging.EventUpdatable;
import com.esri.ges.messaging.GeoEventProducer;
import com.esri.ges.messaging.Messaging;
import com.esri.ges.messaging.MessagingException;
import com.esri.ges.processor.CacheEnabledGeoEventProcessor;
import com.esri.ges.processor.GeoEventProcessorBase;
import com.esri.ges.processor.GeoEventProcessorDefinition;
import com.esri.ges.registry.condition.ConditionRegistry;
import com.esri.ges.spatial.Geometry;
import com.esri.ges.spatial.GeometryType;
import com.esri.ges.spatial.Point;
import com.esri.ges.util.DateUtil;
import com.esri.ges.util.Validator;

public class AutoArrivalDepartureProcessor extends GeoEventProcessorBase implements  EventProducer, EventUpdatable
{
  private static final Log log = LogFactory.getLog( AutoArrivalDepartureProcessor.class );
  private StopsManager stopsManager;
  private RouteManager routeManager;
  private ConditionRegistry conditionRegistry;
  private Map<String, StopIncidentConditions> stopConditions = new ConcurrentHashMap<String,StopIncidentConditions>();
//  private IncidentManager incidentManager;
  private AutoArrivalDepartureManager autoArrivalDepartureManager;
//  private final Map<String, String> incidentIDMapper = new ConcurrentHashMap<String, String>();
  private VehiclesManager vehiclesManager;
  private GeoEventProducer geoEventProducer;
  private EventDestination destination;
  private Messaging messaging;
  

  
  private class StopIncidentConditions
  {
    SpatialCondition open;
    SpatialCondition close;
  }

  protected AutoArrivalDepartureProcessor( GeoEventProcessorDefinition definition,
                           StopsManager stopsManager,
                           RouteManager routeManager,
                           VehiclesManager vehiclesManager,
                           ConditionRegistry conditionRegistry,
                           AutoArrivalDepartureManager autoArrivalDepartureManager,
                           Messaging messaging) throws ComponentException
  {
    super(definition);
    this.stopsManager = stopsManager;
    this.vehiclesManager = vehiclesManager;
    this.routeManager = routeManager;
    this.conditionRegistry = conditionRegistry;
    this.autoArrivalDepartureManager = autoArrivalDepartureManager;
    this.messaging = messaging;
  }

  @Override
  public void afterPropertiesSet()
  {
    super.afterPropertiesSet();
  }
  
  private String buildIncidentCacheKey(GeoEvent geoEvent)
  {
    if (geoEvent != null && geoEvent.getTrackId() != null && geoEvent.getStartTime() != null && geoEvent.getGeometry() != null)
    {
      GeoEventDefinition definition = geoEvent.getGeoEventDefinition();
      return definition.getOwner() + "/" + definition.getName() + "/" + geoEvent.getTrackId();
    }
    return null;
  }
  
  @Override
  public GeoEvent process(GeoEvent geoEvent) throws Exception {
	  String trackId = (String) geoEvent.getField("TRACK_ID");
	  log.info("Processing TRACK_ID" + trackId);
	  Stop stop = getNextStop(trackId);
	  if(stop != null) {
		  log.info(trackId + " Processing stop " + stop);
	  }
	  stop = process2(geoEvent, stop);
	  if(stop != null) {
		  log.info(trackId + " Processed stop " + stop);
	  }
	  List<Stop> stops = new LinkedList<Stop>();
	  stops.add(stop);
	  //if(stop.getSequenceNumber() == 0) {
		  
	  Stop stop1 = getNextStop(trackId);
	 
	  if(stop1.getSequenceNumber() != stop.getSequenceNumber()) {
		  // Helps when in intersecting geofence.
		  log.info(trackId + "=" + "previous sequence = " + stop.getSequenceNumber()+ 
				  "next sequence " + stop1.getSequenceNumber() + " . starting processing again");
		  stop1 = process2(geoEvent, stop1);
		  stops.add(stop1);
	  }
	  //}
	  
	  log.info(trackId + " Returning stops " + stops);
	  
	  for(Stop stopIter: stops) {
		  GeoEvent g = stopsManager.createGeoEvent(stopIter, this.getId(), 
			  this.getDefinition().getUri());
		  log.info(trackId + " sending stop " + stopIter);
		  this.send(g);
	  }
	  return geoEvent;
  }

  
  public Stop process2(GeoEvent geoEvent, Stop stop) throws Exception
  {
    Date eventTime = (Date) geoEvent.getField("TIME_START");
	String trackId = (String) geoEvent.getField("TRACK_ID");
    if (stop == null) // T.M. Will be excuding assigned
    {
      log.error( "Couldn't process for track "+trackId+" because the next stop was not found." );
      return null;
    }
    if (stop.getStatus() == StopStatus.Assigned) // T.M. Will be excuding assigned
    {
    	log.error( "Couldn't process stop = " + stop + " because stop is assigned" );
    	return null;
    }
    
    // T.M. This has been added to cut off
    if(stop.getStatus()==StopStatus.Completed || stop.getStatus()==StopStatus.Exception) {
    	 this.processClosedStop(stop, eventTime);
    	 log.info("This stop has been processed");
    	 return stop;
    }
    
    
    String incidentCacheKey = buildIncidentCacheKey(geoEvent);
    log.info("incidentCacheKey " + incidentCacheKey);
    if( false )//incidentCacheKey != null ) // Short cutting autoarrival
    {
//      String guid = incidentIDMapper.get( incidentCacheKey );
      String guid = autoArrivalDepartureManager.getIncidentId(incidentCacheKey);
      Incident incident = null;
      StopIncidentConditions conditions = stopConditions.get( stop.getName() );
      if( guid != null && autoArrivalDepartureManager.hasIncident( guid ) )
      {
        autoArrivalDepartureManager.updateIncident( guid, geoEvent );
        incident = autoArrivalDepartureManager.getIncidentById(guid);
        log.info("Found Incident in cache for" + stop.getName());
      }
      else
      {
        if( conditions == null )
        {
          log.info("Creating Open Spatial Condition For Stop");	
          conditions = createOpenSpatialConditionForStop( stop );
          stopConditions.put( stop.getName(), conditions );
        }
        if( conditions.open.evaluate( geoEvent ) )
        {
          incident = autoArrivalDepartureManager.openIncident( "Arrive-Depart for Stop "+stop.getName(),
                                                   IncidentType.Cumulative, AlertType.Notification, 
                                                   com.esri.ges.core.incident.GeometryType.Point, conditions.open, 
                                                   conditions.close, geoEvent.getGeoEventDefinition().getOwner(), definition.getUri(), 3600, geoEvent, incidentCacheKey);

          log.info("Creating incident after evaluating open" + stop.getName());
          //          incidentIDMapper.put( incidentCacheKey, incident.getId() );
          if(stop.getStatus()==StopStatus.Dispatched || stop.getStatus()==StopStatus.Assigned)
          {
            stop.setActualArrival( eventTime );
            stop.setStatus( StopStatus.AtStop );
            log.info("Setting stop as AtStop" + stop.getName());
          }
        }
      }
      log.info("Incident " + incident);
      if (incident != null || stop.getSequenceNumber() == 0 || 
    	  stop.getStatus()==StopStatus.Completed || stop.getStatus()==StopStatus.Exception)
      {
	    if(stop.getSequenceNumber() == 0) {
			// TODO: T.M. Should we reset the base locationstop.getStatus() == StopStatus.AtStopbase
			stop.setStatus(StopStatus.Completed);
			log.info("stop sequence number = 0 Setting stop status to completed " + stop);
		}  
    	log.info("Checking if close condition is true " + conditions.close.evaluate(geoEvent));
        if(stop.getStatus()==StopStatus.Completed || stop.getStatus()==StopStatus.Exception  || conditions.close.evaluate(geoEvent) )
        {
          log.info("Closing geofence for " + stop);
//          incidentIDMapper.remove( incidentCacheKey );
//          incidentManager.closeIncident( guid, geoEvent );
          autoArrivalDepartureManager.closeIncident(incidentCacheKey, geoEvent);
          this.processClosedStop(stop, eventTime);
          
        }
      }
    } /*else if(stop.getSequenceNumber() == 0 && 
    		  (stop.getStatus() == StopStatus.AtStop ||
    		  stop.getStatus() == StopStatus.Dispatched))  {
    	stop.setStatus(StopStatus.Completed);
		log.info("Setting stop status to completed " + stop);
		this.processClosedStop(stop, eventTime);
    }*/
    stop.setLastUpdated(eventTime);
    cacheGeoEventWithVehichleResource( geoEvent, trackId );
    return stop;
  }

  private void processClosedStop(Stop stop, Date eventTime) {
	  if(stop.getStatus()==StopStatus.Completed || 
	     stop.getStatus()==StopStatus.Exception ||
	     stop.getStatus()==StopStatus.AtStop)
      {
		  // Question. Should we automatically move to next stop if the stops is AtStop
		  // or wait for completion of stop?.
		  
//        incidentIDMapper.remove( incidentCacheKey );
//        incidentManager.closeIncident( guid, geoEvent );
        
        stopConditions.remove(stop.getName());
        Vehicle vehicle = vehiclesManager.getVehicleByName( stop.getRouteName() );
        vehicle.setNextStopSequenceNumber( stop.getSequenceNumber()+1 );
        log.info("Set vehicle next sequence number "+ vehicle.getNextStopSequenceNumber());
        //if(stop.getStatus()==StopStatus.AtStop)
        //{
          stop.setActualDeparture( eventTime );
          stop.setActualServiceDuration( (int)DateUtil.minutesBetween( stop.getActualArrival(), eventTime ) );
          //stop.setStatus( StopStatus.Completed ); TM: Removing autoComplete
          log.info("closing out stop durations" + stop);
        //}
        
      }
	  
  }
  private void cacheGeoEventWithVehichleResource(GeoEvent geoEvent, String trackId)
  {
    Route route = routeManager.getRouteByName( trackId );
    if( route == null )
    {
      log.error( "Couldn't find route "+trackId+" when trying to cache last GeoEvent." );
    }
    else
    {
      Resource resource = vehiclesManager.getResourceForVehicle( trackId );
      if( resource != null )
      {
        resource.cache( geoEvent );
        Geometry geom = geoEvent.getGeometry();
        if( geom.getType() == GeometryType.Point )
        {
          vehiclesManager.getVehicleByName( trackId ).setLocation( (Point)geom );
        }
      }
      else
      {
        log.info( "Didn't find resource for vehicle "+trackId );
      }
    }
  }

  private GeoEvent createGeoEvent(Stop stop) throws Exception
  {
    return stopsManager.createGeoEvent(stop, "arcgis", definition.getUri() );
  }

  /*private Stop getNextStop(String trackId, boolean forceReset) {
	  
  }*/
  
  private Stop getNextStop(String trackId) {
	  return getNextStop(trackId, true );
  }
  
  /**
   * Returns next stop that is not in assigned
   * 
   * @param trackId
   * @return
   */
  private Stop getNextStop(String trackId, boolean firstRun)
  {
    Vehicle vehicle = vehiclesManager.getVehicleByName( trackId );
    List<Stop> stopsForVehicle = stopsManager.getStopsByRouteName( trackId );
    if( vehicle == null || Validator.isEmpty( stopsForVehicle ) )
    {
      return null;
    }
    log.info("vehicle.getNextStopSequenceNumber()" + vehicle.getNextStopSequenceNumber());
    if(vehicle.getNextStopSequenceNumber()==null) {
      log.info("Reset vehicle next sequenceNumber");
      resetVehicleNextSequenceNumber();
    }
    Integer nextStopSequenceNumber = vehicle.getNextStopSequenceNumber();
    if(firstRun == false) {
    	nextStopSequenceNumber = 0;
    }
	for (; nextStopSequenceNumber <= stopsForVehicle.size(); nextStopSequenceNumber++) {
		if (nextStopSequenceNumber >= stopsForVehicle.size()) {
			if (firstRun == true) {
				return getNextStop(trackId, false);
			}
			log.debug("returning null stop trackid = " + trackId
					+ "nextStopSequenceNumber=" + nextStopSequenceNumber
					+ " > stopsForVehicle.size()=" + stopsForVehicle.size());
			vehicle.setNextStopSequenceNumber(null);
			return null;
		}
		Stop stop = stopsForVehicle.get(nextStopSequenceNumber);
		if (stop.getStatus() == StopStatus.Completed ||
		    stop.getStatus() == StopStatus.Exception ||
		    stop.getStatus() == StopStatus.Assigned || 
		    (stop.getStatus() == StopStatus.AtStop && stop.getActualDeparture() != null)) {
			continue;
		}  else {
			break;

		}
	}
    vehicle.setNextStopSequenceNumber(nextStopSequenceNumber);
    log.info("Processed next stop (getNextStop)" + nextStopSequenceNumber);
    return stopsForVehicle.get( nextStopSequenceNumber );
  }
  
  private void resetVehicleNextSequenceNumber()
  {
    List<Vehicle> vehicles = vehiclesManager.getVehicles();
    for(Vehicle v:vehicles)
    {
      v.setNextStopSequenceNumber(stopsManager.getSequenceNumberForNextAssignedStop(v.getVehicleName()));
    }
  }

  private StopIncidentConditions createOpenSpatialConditionForStop( Stop stop )
  {
    StopIncidentConditions retConditions = new StopIncidentConditions();
    SpatialCondition condition = null;
    ConditionService service = conditionRegistry.getCondition("spatialCondition");
    if (service == null)
      throw new RuntimeException("Spatial conditions are not currently supported: bundle 'Esri :: AGES :: Condition :: Spatial' is not registered.");
    try
    {
//      String geoFenceString = stopsManager.getStopsAoiCategory()+"/"+stop.getName();
      String geoFenceString = stopsManager.getStopsAoiCategory()+"/"+Validator.normalizeName(stop.getName());
      condition = (SpatialCondition)service.create();
      condition.setGeofence( geoFenceString );
      condition.setOperator( SpatialOperator.INSIDE );
      condition.setOperand("GEOMETRY");
      //condition.setGeoEventCache( geoEventCache );
      retConditions.open = condition;
      condition = (SpatialCondition)service.create();
      condition.setGeofence( geoFenceString );
      condition.setOperator( SpatialOperator.OUTSIDE );
      condition.setOperand("GEOMETRY");
     // condition.setGeoEventCache( geoEventCache );
      retConditions.close = condition;
    }
    catch( Exception e)
    {
      throw new RuntimeException(e.getMessage());
    }
    return retConditions;
  }
  
  @Override
  public void send(GeoEvent msg) throws MessagingException
  {
    if(geoEventProducer == null)
    {
      if(messaging == null)
      {
        log.error("Messaging is null.  Unable to create geoEventProducer.");
        return;
      }
      destination = new EventDestination(getId() + ":event");
      geoEventProducer = messaging.createGeoEventProducer(destination.getName());
      if(geoEventProducer == null)
      {
        log.error("Unable to create geoEventProducer.");
        return;
      }
    }
    log.info("GeoEventProducer Senging " + msg);
    geoEventProducer.send(msg);
  }
  
 /* @Override
  public boolean isCacheRequired()
  {
    return false;
  }*/
  
  @Override
  public List<EventDestination> getEventDestinations()
  {
    return Arrays.asList(destination);
  }

  @Override
  public EventDestination getEventDestination()
  {
    return destination;
  }
  
  @Override
  public void setId(String id)
  {
    super.setId(id);
    destination = new EventDestination(getId() + ":event");
    geoEventProducer = messaging.createGeoEventProducer(destination.getName());
    log.info("Setting id = " + id + " destination and geoeventProducer done");
  }

}