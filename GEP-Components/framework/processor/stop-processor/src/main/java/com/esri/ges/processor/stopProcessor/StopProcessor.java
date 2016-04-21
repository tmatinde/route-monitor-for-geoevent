 package com.esri.ges.processor.stopProcessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.esri.ges.core.component.ComponentException;
import com.esri.ges.core.geoevent.FieldDefinition;
import com.esri.ges.core.geoevent.GeoEvent;
import com.esri.ges.core.geoevent.GeoEventCache;
import com.esri.ges.core.geoevent.GeoEventDefinition;
import com.esri.ges.core.geoevent.GeoEventPropertyName;
import com.esri.ges.core.property.Property;
import com.esri.ges.manager.geoeventdefinition.GeoEventDefinitionManagerException;
import com.esri.ges.manager.messages.Message;
import com.esri.ges.manager.messages.MessageType;
import com.esri.ges.manager.messages.MessagesManager;
import com.esri.ges.manager.routes.RouteManager;
import com.esri.ges.manager.stops.DefaultStop;
import com.esri.ges.manager.stops.NonServiceStopType;
import com.esri.ges.manager.stops.Stop;
import com.esri.ges.manager.stops.StopResource;
import com.esri.ges.manager.stops.StopStatus;
import com.esri.ges.manager.stops.StopsManager;
import com.esri.ges.messaging.EventDestination;
import com.esri.ges.messaging.EventProducer;
import com.esri.ges.messaging.EventUpdatable;
import com.esri.ges.messaging.GeoEventCreator;
import com.esri.ges.messaging.GeoEventProducer;
import com.esri.ges.messaging.Messaging;
import com.esri.ges.messaging.MessagingException;
import com.esri.ges.processor.GeoEventProcessorBase;
import com.esri.ges.processor.GeoEventProcessorDefinition;


public class StopProcessor extends GeoEventProcessorBase implements EventProducer, EventUpdatable
{
  private static final Log LOG            = LogFactory.getLog(StopProcessor.class);

  private  StopsManager stopsManager;
  private  RouteManager routeManager;
  private  MessagesManager messagesManager;
  private String stopLoadGEDName = "Route-Stop";
  private String stopUpdateGEDName = "Route-Stop-Update";  
  private String stopUpdateStatus = "Route-Stop-Update-Status";
  private GeoEventProducer geoEventProducer;
  private EventDestination destination;
  private Messaging messaging;
  private GeoEventCreator geoEventCreator;
  private Boolean includeLastStopInRoute = new Boolean(true);
  
  protected StopProcessor(GeoEventProcessorDefinition definition, StopsManager stopsManager, RouteManager routeManager, MessagesManager messagesManager, Messaging messaging) throws ComponentException
  {
    super(definition);
    this.stopsManager = stopsManager;
    this.routeManager = routeManager;
    this.messagesManager = messagesManager;
    this.messaging = messaging;
    this.geoEventCreator = messaging.createGeoEventCreator();

  }
  
  @Override
  public void setId(String id)
  {
    super.setId(id);
    destination = new EventDestination(getId() + ":event");
    geoEventProducer = messaging.createGeoEventProducer(destination.getName());
  
  }
  
	public void afterPropertiesSet() {
		super.afterPropertiesSet();
		Property property = getProperty(StopDefinition.ROUTING_INCLUDE_LAST_STOP);
		if (property != null) {
			String tmp = property.getValueAsString();
			this.includeLastStopInRoute = Val.chkBool(tmp, true);
			LOG.info("includeLastStopInRoute set to "
					+ this.includeLastStopInRoute);
		}
	}

  @Override
  public GeoEvent process(GeoEvent geoEvent) throws Exception
  {
    if(geoEvent.getGeoEventDefinition().getName().equals(stopLoadGEDName))
      processLoadStops(geoEvent);
    else if (geoEvent.getGeoEventDefinition().getName().equals(stopUpdateGEDName))
      processNewOrUpdateStops(geoEvent);
    
    return null;
  }

  protected void processLoadStops(GeoEvent geoEvent)
  {
    Stop stop = stopsManager.createStop((String)geoEvent.getField( StopResource.STOP_NAME_KEY ));
    if(stop != null)
    {
      stopsManager.convertGeoEventToStop(geoEvent, stop);
    }
//    Stop updatedStop = stopsManager.addOrReplaceStop(stop);
    stop.setProjectedArrival(stop.getScheduledArrival());
    stop.setProjectedDeparture(stop.getScheduledDeparture());
    //return stopsManager.createGeoEvent(updatedStop, this.getDefinition().getName(), this.getDefinition().getUri());
    Stop updatedStop = stopsManager.addOrReplaceStop(stop);
    updateStop(updatedStop);
  }
  
  protected void processNewOrUpdateStops(GeoEvent geoEvent) throws Exception
  {
    String requestId = (String)geoEvent.getField( "RequestId" );
    String stopName = (String)geoEvent.getField( StopResource.STOP_NAME_KEY );
    Stop stop = stopsManager.getStopByName(stopName);
    LOG.info("Stop in memory = " + stop);
    String newStatusStr = (String)geoEvent.getField( "STATUS" );
    String routeName = (String)geoEvent.getField( "ROUTE_NAME" );
    String newType = (String)geoEvent.getField( "TYPE" );
    StopStatus newStatus = StopStatus.valueOf(newStatusStr);
    int newScheduledDuration = geoEvent.getField( "SCHEDULED_SERVICE_DURATION" )==null?-1:(Integer)geoEvent.getField( "SCHEDULED_SERVICE_DURATION" );
    int newSequenceNumber = geoEvent.getField( "SEQUENCE_NUMBER" )==null?-1:(Integer)geoEvent.getField( "SEQUENCE_NUMBER" );
    LOG.info("Processing new or updated stops = " + stopName );
    if(stop==null)
    {
      LOG.info("stop is null stopname = " + stopName );
      if(newStatus==StopStatus.Unassigned)
      {
        LOG.info("stop new status is unassigned = " + stopName );
        stop = stopsManager.createStop(stopName);
        stopsManager.convertGeoEventToStop(geoEvent, stop);
        DefaultStop defaultStop = new DefaultStop(stop);
        defaultStop.setRouteName(stopsManager.getUnassignedRouteName());
        defaultStop.setSequenceNumber(0);
        stop = defaultStop;
        LOG.info("new stop adding unassigned = " + stopName );
        Stop updatedStop = stopsManager.addOrReplaceStop(stop);
      
        // Inserting unassigned stops
        updateStop(updatedStop);
      }
      else
      {
        LOG.info("stop is not unassigned stopname = " + stopName );
        // Inserting assigned stops
//        String routeName = updatedStop.getRouteName();
        DefaultStop newDefaultStop = new DefaultStop();
        newDefaultStop.setType(newType);
        LOG.info("Set Sequence Number = " + newSequenceNumber + "for " + stopName + ", RouteName = "+ routeName);	
        newDefaultStop.setSequenceNumber(newSequenceNumber);
        newDefaultStop.setName(stopName);
        newDefaultStop.setRouteName(routeName);
        Stop newstop = new DefaultStop(newDefaultStop);
        String errorMessage = validateSequenceChange(newstop, newSequenceNumber);
        LOG.info("Error message" + errorMessage + "stopname = " + stopName );
        if(errorMessage.length()==0)
        {
          LOG.info("addomgnew stop adding assigned = " + stopName ); 
          stop = stopsManager.createStop(stopName);
          stopsManager.convertGeoEventToStop(geoEvent, stop);
          Stop updatedStop = stopsManager.addOrReplaceStop(stop);
          boolean optimize = updatedStop.getSequenceNumber()==null;
          LOG.info("Updating route = " + stopName + " routeName = " + routeName );
          updateRoute(routeName, optimize, true, requestId);
        }
        else
        {
          sendErrorMessage(errorMessage, requestId);
        }
      }
    }
    else
    {
      // Updating existing stops
//      String routeName = stop.getRouteName();
      LOG.info("updating existing stop = " + stopName + "stop before update = " + stop );
      StopStatus previousStatus = stop.getStatus();
      int previousSequence = stop.getSequenceNumber();
      int previousDuration = stop.getScheduledServiceDuration();
      String routeNameOfStop = getRouteOfStop(stop.getName());
      String routeNameOfCanceledStop = getRouteOfCanceledStop(stop.getName());
      
      int sequenceOfCanceledStop = getSequenceNumberOfCanceledStop(stop.getName());

      if(newStatus == previousStatus)
      {
        LOG.info("newstatus == previoudStatus == " + previousStatus  + " "+ stopName + ", stop = " + stop);
        // If status is canceled, we don't need to update it.
        if(newStatus != StopStatus.Canceled)
        {
          LOG.info("newstatus != canceled " + " "+ stopName );
          if( (newScheduledDuration > 0 && previousDuration != newScheduledDuration) || (newSequenceNumber >= 0 && previousSequence != newSequenceNumber) )
          {
            // This is the case for stop duration or sequence updates.
            String errorMessage = validateSequenceChange(stop, newSequenceNumber);
            if(errorMessage.length()==0)
            {
              stopsManager.convertGeoEventToStop(geoEvent, stop);
              LOG.info("Stop after absorbing geoEvent " + stop);
              stopsManager.addOrReplaceStop(stop);
              updateRoute(routeName, false, true, requestId);
            }
            else
              sendErrorMessage(errorMessage, requestId);
            
          }
          else  // This is the case for updates other than status, sequence and duration.
          {
        	
            stopsManager.convertGeoEventToStop(geoEvent, stop);
            LOG.info("Updated stop, same status but change it according to incoming geoEvent" + stop);
            updateStop(stop);
          }
        }
      }
      else
      {
        // T.M.  Added to perform a memory merge	  
    	LOG.info("Attempting status to be updated from " + previousStatus + " to "+ stop.getStatus() + " for stop = " + stop );
    	if(previousStatus == StopStatus.Completed || previousStatus == StopStatus.Exception) {
        	// T.M. Added so that we never flip back a completed
        	LOG.info("Cannot change stop from completed or exception to " +  stop.getStatus() +  " for stop = " + stop);
        	return;
        }
    	
		LOG.info("Creating " + stopUpdateStatus + " owner = arcgis" );
		GeoEvent geoEventUpdate = this.geoEventCreator.create(
						stopUpdateStatus, "arcgis");
		List<FieldDefinition> lstFieldDef = geoEventUpdate
				.getGeoEventDefinition().getFieldDefinitions();
		Set<String> updateKeys = new HashSet<String>();
		for (FieldDefinition fd : lstFieldDef) {
			updateKeys.add(fd.getName());
		}
		
        stopsManager.convertGeoEventToStop(geoEvent, stop, updateKeys); //10-15-2015 T.M.  We have just overwritten all our stop, with the geoevent
        
        if(previousStatus == StopStatus.AtStop && 
        		stop.getStatus() == StopStatus.Dispatched) {
        	// T.M. Added to accomadate new FWW workflow
        	LOG.info("From at stop to dispatched " + stopName);
        	updateStopStatusUpdate(stop);
        }
        if(previousStatus == StopStatus.Dispatched && stop.getStatus() == StopStatus.AtStop)
        {
          // This is the case for arriving
          if(stop.getActualArrival()==null)
            stop.setActualArrival(stop.getLastUpdated());
          
          LOG.info("Updating stop previous status is dispatched and current stop status at stop " + stopName);
          updateStopStatusUpdate(stop);
        }
        if(/*previousStatus == StopStatus.AtStop && T.M.  Removed this because sometimes it is set by the dispatcher*/
           (stop.getStatus() == StopStatus.Completed || stop.getStatus() == StopStatus.Exception))
        {
          if(!stop.getType().equals(NonServiceStopType.Base.toString())) {
        	  // TM: Added so that base is set to completed automatically.  Needed for re-sequencing
        	  // so that completed stop does not go on-top of base stop
        	  LOG.info("Testing if base stop for this route has been completed");
        	  List<Stop> stops = this.stopsManager.getStopsByRouteName(stop.getRouteName());
        	  for(Stop rStop : stops) {
        		  if(rStop.getType().equals(NonServiceStopType.Base.toString())) {
        			  if(!(rStop.getStatus().equals(StopStatus.Completed) || rStop.getStatus().equals(StopStatus.Exception))) {
        				  LOG.info("Setting base stop to completed automatically");
        				  rStop.setStatus(StopStatus.Completed);
        				  updateStopStatusUpdate(rStop);
        			  }
        			  LOG.info("Finished testing base stop");
        			  break;
        		  }
        	  }
          }
         
          // This is the case for completing or exception
          /*if(stop.getActualDeparture() == null)
          {
            stop.setActualDeparture(stop.getLastUpdated());
            if(stop.getActualArrival() != null)
            {
              long diff = ((stop.getActualDeparture().getTime()/60000) - (stop.getActualArrival().getTime()/60000));
              // TODO: Change database type to from small int to int.
              if(diff > 32767)
                diff = 32767;
              stop.setActualServiceDuration((int) diff);
            }
          }*/
//          Vehicle vehicle = vehiclesManager.getVehicleByName( stop.getRouteName() );
//          vehicle.setNextStopSequenceNumber( stop.getSequenceNumber()+1 );
          LOG.info("new status is completed or status is exception");
          updateStopStatusUpdate(stop);
        }
        if(stop.getStatus() == StopStatus.Canceled)
        {
          LOG.info("Stop status is canceled" + " "+ stopName) ;
          Stop preStop = null; //stop.getSequenceNumber()>=1?stops.get(stop.getSequenceNumber()-1):null;
          Stop postStop = null; // stops.size()>stop.getSequenceNumber() + 1?stops.get(stop.getSequenceNumber()+1):null;
          if(!routeNameOfCanceledStop.equals(""))
          {
            List<Stop> stops = stopsManager.getStopsByRouteName(routeNameOfCanceledStop);
            if(sequenceOfCanceledStop >=1 && sequenceOfCanceledStop < stops.size()-1)
            {
              preStop = stops.get(sequenceOfCanceledStop-1);
              postStop = stops.get(sequenceOfCanceledStop+1);
            }
          }
          /*if(!routeNameOfCanceledStop.equals(""))
          {
        	  List<Stop> stops = stopsManager.getStopsByRouteName(routeNameOfCanceledStop);
        	  if(sequenceOfCanceledStop >=1 && sequenceOfCanceledStop < stops.size()-1)
        	  {
        		  preStop = stops.get(sequenceOfCanceledStop-1);
        		  postStop = stops.get(sequenceOfCanceledStop+1);
        	  }
          }*/
          
          // Canceled
          DefaultStop defaultStop = new DefaultStop(stop);
          defaultStop.setRouteName(stopsManager.getCanceledRouteName());
          defaultStop.setSequenceNumber(0);
          stop = defaultStop;
          Stop updatedStop = stopsManager.addOrReplaceStop(stop);
          LOG.info("Updating stop" + " "+ stopName);
          updateStop(updatedStop);
          
          // if the canceled stop has breaks before and after
          
          
          if(preStop != null && postStop != null)
          {
            if(preStop.getType().equals(NonServiceStopType.Break.toString()) && postStop.getType().equals(NonServiceStopType.Break.toString()))
            {
              DefaultStop defaultPostStop = new DefaultStop(postStop);
              defaultPostStop.setRouteName(stopsManager.getCanceledRouteName());
              defaultPostStop.setSequenceNumber(0);
              defaultPostStop.setStatus(StopStatus.Canceled);
              postStop = defaultPostStop;
              Stop updatedPostStop = stopsManager.addOrReplaceStop(postStop);
              updateStop(updatedPostStop);
            }
          }
          if(!routeNameOfCanceledStop.equals(stopsManager.getUnassignedRouteName()))
            updateRoute(routeNameOfCanceledStop, false, true, requestId);
          if(previousStatus == StopStatus.Dispatched || previousStatus == StopStatus.AtStop) // TODO: What about if assigned or unassigned
          {
            sendCancelMessage(stop, routeNameOfCanceledStop);
          }
        }
      }
    }
  }
  
  private String validateSequenceChange(Stop stop, int newSequenceNumber)
  {
    
    List<Stop> stops = stopsManager.getStopsByRouteName(stop.getRouteName());
    if(stops == null || stops.isEmpty())
    {
      return "No stops found on the route " + stop.getRouteName();
    }

    // Build the new list
    List<Stop> newlist = new ArrayList<Stop>();
    for (Stop s : stops)
    {
      if(s != null)
      {
        if (!s.getName().equals(stop.getName()))
        {
          if (newlist.size() == newSequenceNumber)
            newlist.add(stop);
          newlist.add(s);
        }
      }
    }
    // Are there two breaks together?
    String previousType = "";
    int newseq = 0;
    for (Stop s : newlist)
    {
      if(s.getType().equals(NonServiceStopType.Base.toString()))
      {
        if(newseq != 0 && newseq != newlist.size()-1)
          return "Base stops cannot be moved away from start or end.  ";
      }
      if (s.getType().equals(NonServiceStopType.Break.toString()) && previousType.equals(NonServiceStopType.Break.toString()))
      {
        return "Two breaks cannot be next to each other.";
      }
      previousType = s.getType();
      newseq++;
    }

    return "";
  }
  
  private String getRouteOfStop(String stopName)
  {
    Stop stop = stopsManager.getStopByName(stopName);
    if(stop != null)
      return stop.getRouteName();
    else
      return "";
  }
  
  private int getSequenceNumberOfCanceledStop(String stopName)
  {
    Stop stop = stopsManager.getStopByName(stopName);
    if(stop != null)
      return stop.getSequenceNumber();
    else
      return -1;
  }
  
  private void updateStopStatusUpdate(Stop stop)
  {
    try
    {
      //Stop updatedStop = stopsManager.addOrReplaceStop(stop);
      GeoEvent gvt = stopsManager.createGeoEventStatusUpdate(stop, getId(), 
      		  this.getDefinition().getUri());
      
      gvt.setProperty(GeoEventPropertyName.TYPE, "event");
      gvt.setProperty(GeoEventPropertyName.OWNER_ID, getId());
      gvt.setProperty(GeoEventPropertyName.OWNER_URI, definition.getUri());
      send(gvt);
      LOG.info("Sent updateStopStatusUpdate geovent = " + gvt);
    }
    catch(MessagingException e)
    {
      LOG.error("Unable generate stop update geoevent.  Error message is: " + e.getMessage());
    }
    catch(Exception e)
    {
      LOG.error("Unable generate stop update geoevent.  Error message is: " + e.getMessage());
    }
  }
  
  private void updateStop(Stop stop)
  {
    try
    {
      //Stop updatedStop = stopsManager.addOrReplaceStop(stop);
      send(stopsManager.createGeoEvent(stop, getId(), this.getDefinition().getUri()));
    }
    catch(MessagingException e)
    {
      LOG.error("Unable generate stop update geoevent.  Error message is: " + e.getMessage());
    }
    catch(Exception e)
    {
      LOG.error("Unable generate stop update geoevent.  Error message is: " + e.getMessage());
    }
  }
 
  private void updateRoute(String routeName, boolean optimize, boolean commit, String requestId)
  {
    try
    {
      send(routeManager.createUpdateRouteGeoEvent(routeName, optimize, commit, this.includeLastStopInRoute,  requestId, getId(), this.getDefinition().getUri()));
    }
    catch(MessagingException e)
    {
      LOG.error("Unable generate route update geoevent.  Error message is: " + e.getMessage());
    }
    catch(Exception e)
    {
      LOG.error("Unable generate route update geoevent.  Error message is: " + e.getMessage());
    }
  }
  
  private void sendCancelMessage(Stop stop, String routeName)
  {
    Message message = new Message();
    message.setType(MessageType.Notification.toString());
    message.setSubject("Cancel");
    message.setMessageFrom("RouteMonitor");
    message.setMessageTo(routeName);
    message.setMessageBody(stop.getName() + " is canceled.");
    message.setCallback("");
    try
    {
      send(messagesManager.createGeoEvent(message, getId(), this.getDefinition().getUri()));
    }
    catch (MessagingException e)
    {
      LOG.error("Unable generate message geoevent.  Error message is: " + e.getMessage());
    }
    catch(Exception e)
    {
      LOG.error("Unable generate message geoevent.  Error message is: " + e.getMessage());
    }
  }
  
  private void sendErrorMessage(String messageBody, String messageTo)
  {
    Message message = new Message();
    message.setType(MessageType.Notification.toString());
    message.setSubject("Stop Update Error");
    message.setMessageFrom("RouteMonitor");
    message.setMessageTo(messageTo);
    message.setMessageBody(messageBody);
    message.setCallback("");
    try
    {
      send(messagesManager.createGeoEvent(message, getId(), this.getDefinition().getUri()));
    }
    catch (MessagingException e)
    {
      LOG.error("Unable generate message geoevent.  Error message is: " + e.getMessage());
    }
    catch(Exception e)
    {
      LOG.error("Unable generate message geoevent.  Error message is: " + e.getMessage());
    }
  }

  @Override
  public void send(GeoEvent msg) throws MessagingException
  {
    if(geoEventProducer == null)
    {
      if(messaging == null)
      {
        LOG.error("Messaging is null.  Unable to create geoEventProducer.");
        return;
      }
      destination = new EventDestination(getId() + ":event");
      geoEventProducer = messaging.createGeoEventProducer(destination.getName());
      if(geoEventProducer == null)
      {
        LOG.error("Unable to create geoEventProducer.");
        return;
      }
    }
    geoEventProducer.send(msg);
  }

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
  
  private String getRouteOfCanceledStop(String stopName)
  {
    Stop stop = stopsManager.getStopByName(stopName);
    if(stop != null)
      return stop.getRouteName();
    else
      return "";
  }
  
}