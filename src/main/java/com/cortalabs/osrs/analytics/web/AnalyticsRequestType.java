package com.cortalabs.osrs.analytics.web;

import lombok.Getter;

@Getter
public enum AnalyticsRequestType
{
	COMPETITIONS_ONGOING("ongoing"),
	COMPETITIONS_UPCOMING("upcoming");

	final String name;

	AnalyticsRequestType(String name)
	{
		this.name = name;
	}
}
