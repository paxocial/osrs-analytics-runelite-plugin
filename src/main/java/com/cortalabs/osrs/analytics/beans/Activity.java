package com.cortalabs.osrs.analytics.beans;

import lombok.Value;

@Value
public class Activity
{
	String metric;
	int score;
	int rank;
}
