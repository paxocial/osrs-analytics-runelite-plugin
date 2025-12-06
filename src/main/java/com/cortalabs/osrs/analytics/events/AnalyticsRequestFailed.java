package com.cortalabs.osrs.analytics.events;

import lombok.Value;
import com.cortalabs.osrs.analytics.web.AnalyticsRequestType;

@Value
public class AnalyticsRequestFailed
{
	String username;
	AnalyticsRequestType type;
}
