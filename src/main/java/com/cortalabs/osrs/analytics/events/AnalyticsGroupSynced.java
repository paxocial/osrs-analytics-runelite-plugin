package com.cortalabs.osrs.analytics.events;

import com.cortalabs.osrs.analytics.beans.GroupInfoWithMemberships;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AnalyticsGroupSynced
{
	GroupInfoWithMemberships groupInfo;
	boolean silent;

	public AnalyticsGroupSynced(GroupInfoWithMemberships groupInfo)
	{
		this(groupInfo, false);
	}
}
