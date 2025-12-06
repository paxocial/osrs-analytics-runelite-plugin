package com.cortalabs.osrs.analytics.beans;

import lombok.Value;

@Value
public class Skill
{
	String metric;
	long experience;
	int rank;
	int level;
	double ehp;
}
