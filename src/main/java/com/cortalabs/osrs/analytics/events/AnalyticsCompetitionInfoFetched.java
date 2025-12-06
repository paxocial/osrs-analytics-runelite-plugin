package com.cortalabs.osrs.analytics.events;

import com.cortalabs.osrs.analytics.beans.CompetitionInfo;
import lombok.Value;

@Value
public class AnalyticsCompetitionInfoFetched
{
	CompetitionInfo comp;
}
