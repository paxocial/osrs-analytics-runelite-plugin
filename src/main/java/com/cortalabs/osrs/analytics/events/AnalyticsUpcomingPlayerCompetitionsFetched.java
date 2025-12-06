package com.cortalabs.osrs.analytics.events;

import com.cortalabs.osrs.analytics.beans.ParticipantWithCompetition;
import lombok.Value;

@Value
public class AnalyticsUpcomingPlayerCompetitionsFetched
{
	String username;
	ParticipantWithCompetition[] competitions;
}
