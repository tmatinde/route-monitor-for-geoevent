package com.esri.ges.processor.stopProcessor;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.esri.ges.core.property.PropertyDefinition;
import com.esri.ges.core.property.PropertyType;
import com.esri.ges.processor.GeoEventProcessorDefinitionBase;

public class StopDefinition extends GeoEventProcessorDefinitionBase {
	
	private static final Log LOG = LogFactory.getLog(StopDefinition.class);
	public static String ROUTING_INCLUDE_LAST_STOP = "ROUTING_INCLUDE_LAST_STOP";

	public StopDefinition() {
		try {
			propertyDefinitions
					.put(ROUTING_INCLUDE_LAST_STOP,
							new PropertyDefinition(
									ROUTING_INCLUDE_LAST_STOP,
									PropertyType.Boolean,
									new Boolean("false"),
									"Routing Include Last Stop",
									"While computing routing, last stop should be Included.",
									true, false));
		} catch (Exception e) {
			LOG.error("Error setting up stop Definition.", e);
		}
	}

	@Override
	public String getName() {
		return "StopUpdater";
	}

	@Override
	public String getLabel() {
		return "Stop Updater";
	}
}