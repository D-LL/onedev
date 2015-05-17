package com.pmease.gitplex.core.manager.impl;

import java.util.Collection;
import java.util.Iterator;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.hibernate.criterion.Restrictions;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.pmease.commons.hibernate.Sessional;
import com.pmease.commons.hibernate.dao.Dao;
import com.pmease.commons.hibernate.dao.EntityCriteria;
import com.pmease.gitplex.core.manager.TeamManager;
import com.pmease.gitplex.core.manager.UserManager;
import com.pmease.gitplex.core.model.Team;
import com.pmease.gitplex.core.model.User;
import com.pmease.gitplex.core.permission.operation.RepositoryOperation;

@Singleton
public class DefaultTeamManager implements TeamManager {

	private static class BuiltInTeam {
		final Long anonymousId;
		final Long ownersId;
		final Long loggedInId;
		
		BuiltInTeam(Long anonymousId, Long ownersId, Long loggedInId) {
			this.anonymousId = anonymousId;
			this.ownersId = ownersId;
			this.loggedInId = loggedInId;
		}
	}

	private final Dao dao;
	
	private final LoadingCache<Long, BuiltInTeam> builtInTeamsCache;
	
	@Inject
	public DefaultTeamManager(Dao dao, final UserManager userManager) {
		this.dao = dao;
		
		builtInTeamsCache =
				CacheBuilder.newBuilder()
					.build(new CacheLoader<Long, BuiltInTeam>() {

						@Override
						public BuiltInTeam load(Long key) throws Exception {
							User user = DefaultTeamManager.this.dao.get(User.class, key);
							Team anonymous = findBy(user, Team.ANONYMOUS);
							Team owners = findBy(user, Team.OWNERS);
							Team loggedIn = findBy(user, Team.LOGGEDIN);
							return new BuiltInTeam(anonymous.getId(), owners.getId(), loggedIn.getId());
						}
					});
	}

	@Sessional
	@Override
	public Team findBy(User owner, String teamName) {
		return dao.find(EntityCriteria.of(Team.class)
				.add(Restrictions.eq("owner", owner))
				.add(Restrictions.eq("name", teamName)));
	}

	@Override
	public Team getAnonymous(User user) {
		return dao.load(Team.class, builtInTeamsCache.getUnchecked(user.getId()).anonymousId);
	}

	@Override
	public Team getLoggedIn(User user) {
		return dao.load(Team.class, builtInTeamsCache.getUnchecked(user.getId()).loggedInId);
	}

	@Override
	public Team getOwners(User user) {
		return dao.load(Team.class, builtInTeamsCache.getUnchecked(user.getId()).ownersId);
	}

	@Override
	public RepositoryOperation getActualAuthorizedOperation(Team team) {
		if (team.isOwners()) {
			return RepositoryOperation.ADMIN;
		} else if (team.isAnonymous()) {
			return team.getAuthorizedOperation();
		} else if (team.isLoggedIn()) {
			return RepositoryOperation.mostPermissive(team.getAuthorizedOperation(), 
					getAnonymous(team.getOwner()).getAuthorizedOperation());
		} else {
			return RepositoryOperation.mostPermissive(team.getAuthorizedOperation(), 
					getAnonymous(team.getOwner()).getAuthorizedOperation(), 
					getLoggedIn(team.getOwner()).getAuthorizedOperation());
		}
	}

	@Override
	public void trim(Collection<Long> teamIds) {
		for (Iterator<Long> it = teamIds.iterator(); it.hasNext();) {
			if (dao.get(Team.class, it.next()) == null)
				it.remove();
		}
	}
}
