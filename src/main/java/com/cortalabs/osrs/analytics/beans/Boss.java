package com.cortalabs.osrs.analytics.beans;

import lombok.Value;

@Value
public class Boss
{
	String metric;
	int kills;
	int rank;
	double ehb;
}
