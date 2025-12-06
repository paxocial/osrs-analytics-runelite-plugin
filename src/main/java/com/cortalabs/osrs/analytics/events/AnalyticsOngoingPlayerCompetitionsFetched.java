package com.cortalabs.osrs.analytics.events;

import com.cortalabs.osrs.analytics.beans.ParticipantWithStanding;
import lombok.Value;

@Value
public class AnalyticsOngoingPlayerCompetitionsFetched
{
	String username;
	ParticipantWithStanding[] competitions;
}
