/* Copyright © "Open Digital Education", 2014
 *
 * This program is published by "Open Digital Education".
 * You must indicate the name of the software and the company in any production /contribution
 * using the software and indicate on the home page of the software industry in question,
 * "powered by Open Digital Education" with a reference to the website: https://opendigitaleducation.com/.
 *
 * This program is free software, licensed under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, version 3 of the License.
 *
 * You can redistribute this application and/or modify it since you respect the terms of the GNU Affero General Public License.
 * If you modify the source code and then use this modified source code in your creation, you must make available the source code of your modifications.
 *
 * You should have received a copy of the GNU Affero General Public License along with the software.
 * If not, please see : <http://www.gnu.org/licenses/>. Full compliance requires reading the terms of this license and following its directives.

 *
 */

package org.entcore.directory.services.impl;

import fr.wseduc.webutils.Either;
import fr.wseduc.webutils.Utils;
import fr.wseduc.webutils.email.EmailSender;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.entcore.common.neo4j.Neo4j;
import org.entcore.common.neo4j.Neo4jResult;
import org.entcore.common.user.UserInfos;
import org.entcore.common.utils.StringUtils;
import org.entcore.common.validation.StringValidation;
import org.entcore.directory.Directory;
import org.entcore.directory.services.UserBookService;
import org.entcore.directory.services.UserService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static fr.wseduc.webutils.Utils.*;
import static fr.wseduc.webutils.Utils.getOrElse;
import static org.entcore.common.neo4j.Neo4jResult.*;
import static org.entcore.common.user.DefaultFunctions.*;

public class DefaultUserService implements UserService {

	private static final int LIMIT = 1000;
	private final Neo4j neo = Neo4j.getInstance();
	private final EmailSender notification;
	private final EventBus eb;
	private final JsonObject userBookData;
	private Logger logger = LoggerFactory.getLogger(DefaultUserService.class);

	public DefaultUserService(EmailSender notification, EventBus eb, JsonObject aUserBookData) {
		this.userBookData = aUserBookData;
		this.notification = notification;
		this.eb = eb;
	}

	@Override
	public void createInStructure(String structureId, JsonObject user, Handler<Either<String, JsonObject>> result) {
		user.put("profiles", new fr.wseduc.webutils.collections.JsonArray().add(user.getString("type")));
		JsonObject action = new JsonObject()
				.put("action", "manual-create-user")
				.put("structureId", structureId)
				.put("profile", user.getString("type"))
				.put("data", user);
		eb.send(Directory.FEEDER, action, handlerToAsyncHandler(validUniqueResultHandler(result)));
	}

	@Override
	public void createInClass(String classId, JsonObject user, Handler<Either<String, JsonObject>> result) {
		user.put("profiles", new fr.wseduc.webutils.collections.JsonArray().add(user.getString("type")));
		JsonObject action = new JsonObject()
				.put("action", "manual-create-user")
				.put("classId", classId)
				.put("profile", user.getString("type"))
				.put("data", user);
		eb.send(Directory.FEEDER, action, handlerToAsyncHandler(validUniqueResultHandler(result)));
	}

	@Override
	public void update(final String id, final JsonObject user, final Handler<Either<String, JsonObject>> result) {
		JsonObject action = new JsonObject()
				.put("action", "manual-update-user")
				.put("userId", id)
				.put("data", user);
		eb.send(Directory.FEEDER, action, handlerToAsyncHandler(validUniqueResultHandler(result)));
	}

	@Override
	public void updateLogin(final String id, final String newLogin, final Handler<Either<String, JsonObject>> result)
	{
		JsonObject action = new JsonObject()
				.put("action", "manual-update-user-login")
				.put("userId", id)
				.put("login", newLogin);
		eb.send(Directory.FEEDER, action, handlerToAsyncHandler(validUniqueResultHandler(result)));
	}

	@Override
	public void sendUserCreatedEmail(final HttpServerRequest request, String userId,
			final Handler<Either<String, Boolean>> result) {
		String query =
				"MATCH (u:`User` { id : {id}}) WHERE NOT(u.email IS NULL) AND NOT(u.activationCode IS NULL) " +
				"RETURN u.login as login, u.email as email, u.activationCode as activationCode ";
		JsonObject params = new JsonObject().put("id", userId);
		neo.execute(query, params, new Handler<Message<JsonObject>>() {
			@Override
			public void handle(Message<JsonObject> m) {
				Either<String, JsonObject> r = validUniqueResult(m);
				if (r.isRight()) {
					JsonObject j = r.right().getValue();
					String email = j.getString("email");
					String login = j.getString("login");
					String activationCode = j.getString("activationCode");
					if (email == null || login == null || activationCode == null ||
							email.trim().isEmpty() || login.trim().isEmpty() || activationCode.trim().isEmpty()) {
						result.handle(new Either.Left<String, Boolean>("user.invalid.values"));
						return;
					}
					JsonObject json = new JsonObject()
							.put("activationUri", notification.getHost(request) +
									"/auth/activation?login=" + login +
									"&activationCode=" + activationCode)
							.put("host", notification.getHost(request))
							.put("login", login);
					logger.debug(json.encode());
					notification.sendEmail(request, email, null, null,
							"email.user.created.info", "email/userCreated.html", json, true,
							new Handler<AsyncResult<Message<JsonObject>>>() {

								@Override
								public void handle(AsyncResult<Message<JsonObject>> ar) {
									if (ar.succeeded()) {
										result.handle(new Either.Right<String, Boolean>(true));
									} else {
										result.handle(new Either.Left<String, Boolean>(ar.cause().getMessage()));
									}
								}
							});
				} else {
					result.handle(new Either.Left<String, Boolean>(r.left().getValue()));
				}
			}
		});
	}

	@Override
	public void getForExternalService(String id, Handler<Either<String, JsonObject>> handler) {
		final JsonArray filter = createExternalFilter();
		get(id, true, filter, true, result -> {
			if (result.isRight()) {
				JsonObject resultJson = result.right().getValue();
				JsonArray structuresInfos = new JsonArray().add("UAI").add("name").add("externalId");
				extractInfosFromStructures(resultJson, structuresInfos);
				handler.handle(new Either.Right<>(resultJson));
			} else {
				handler.handle(new Either.Left<>("Problem with get in DefaultUserService : " + result.left().getValue()));
			}
		});
	}

	private void extractInfosFromStructures(JsonObject resultJson, JsonArray structuresInfos) {
		JsonArray structures = resultJson.getJsonArray("structureNodes");
		JsonArray reformatStructures = new JsonArray();
		for(Object structure : structures){
			JsonObject structureJson = (JsonObject) structure;
			JsonObject infos = new JsonObject();
			for(Object info : structuresInfos){
				infos.put((String) info,structureJson.getString((String) info));
			}
			reformatStructures.add(infos);
		}
		resultJson.put("structures",reformatStructures);
		resultJson.remove("structureNodes");
	}

	private JsonArray createExternalFilter() {
		return new JsonArray()
				.add("activationCode").add("mobile").add("mobilePhone").add("surname").add("lastLogin").add("created")
				.add("modified").add("ine").add("workPhone").add("homePhone").add("country").add("zipCode")
				.add("address").add("postbox").add("city").add("otherNames").add("title").add("functions")
				.add("lastDomain").add("displayName").add("source").add("login").add("teaches").add("headTeacher")
				.add("externalId").add("joinKey").add("birthDate").add("modules").add("lastScheme").add("addressDiffusion")
				.add("isTeacher").add("structures").add("type").add("children").add("parents").add("functionalGroups")
				.add("administrativeStructures").add("subjectCodes").add("fieldOfStudyLabels").add("startDateClasses")
				.add("scholarshipHolder").add("attachmentId").add("fieldOfStudy").add("module").add("transport")
				.add("accommodation").add("status").add("relative").add("moduleName").add("sector").add("level")
				.add("relativeAddress").add("classCategories").add("subjectTaught").add("needRevalidateTerms")
				.add("email").add("emailAcademy").add("emailInternal").add("lastName").add("firstName");
	}

	@Override
	public void getForETude(String id, Handler<Either<String, JsonObject>> handler) {
		final JsonArray filter = createExternalFilter();
		filter.remove("children");
		filter.remove("parents");
		filter.remove("functionalGroups");
		filter.remove("level");
		filter.remove("lastName");
		filter.remove("firstName");
		get(id, true, filter, true, result -> {
			if (result.isRight()) {
				JsonObject resultJson = result.right().getValue();
				JsonArray structuresInfos = new JsonArray().add("UAI");
				extractInfosFromStructures(resultJson, structuresInfos);
				handler.handle(new Either.Right<>(resultJson));
			} else {
				handler.handle(new Either.Left<>("Problem with get in DefaultUserService : " + result.left().getValue()));
			}
		});
	}

	@Override
	public void get(String id, boolean getManualGroups, boolean filterNullReturn, Handler<Either<String, JsonObject>> result) {
		get(id, getManualGroups, new JsonArray(), filterNullReturn, result);
	}

	@Override
	public void get(String id, boolean getManualGroups, JsonArray filterAttributes, boolean filterNullReturn,
					Handler<Either<String, JsonObject>> result) {

		String getMgroups = "";
		String resultMgroups = "";
		if (getManualGroups) {
			getMgroups = "OPTIONAL MATCH u-[:IN]->(mgroup: ManualGroup) WITH COLLECT(distinct {id: mgroup.id, name: mgroup.name}) as manualGroups, subjectCodes, admStruct, admGroups, parents, children, functions, u, structureNodes ";
			resultMgroups = "CASE WHEN manualGroups IS NULL THEN [] ELSE manualGroups END as manualGroups, ";
		}
		String query =
				"MATCH (u:`User` { id : {id}}) " +
				"OPTIONAL MATCH u-[:IN]->(:ProfileGroup)-[:DEPENDS]->(s:Structure) WITH COLLECT(distinct s) as structureNodes, u " +
				"OPTIONAL MATCH u-[rf:HAS_FUNCTION]->fg-[:CONTAINS_FUNCTION*0..1]->(f:Function) WITH COLLECT(distinct [f.externalId, rf.scope]) as functions, u, structureNodes " +
				"OPTIONAL MATCH u<-[:RELATED]-(child: User) WITH COLLECT(distinct {id: child.id, displayName: child.displayName, externalId: child.externalId}) as children, functions, u, structureNodes " +
				"OPTIONAL MATCH u-[:RELATED]->(parent: User) WITH COLLECT(distinct {id: parent.id, displayName: parent.displayName, externalId: parent.externalId}) as parents, children, functions, u, structureNodes " +
				"OPTIONAL MATCH u-[:IN]->(fgroup: FunctionalGroup) WITH COLLECT(distinct {id: fgroup.id, name: fgroup.name}) as admGroups, parents, children, functions, u, structureNodes " +
				"OPTIONAL MATCH u-[:ADMINISTRATIVE_ATTACHMENT]->(admStruct: Structure) WITH COLLECT(distinct {id: admStruct.id}) as admStruct, admGroups, parents, children, functions, u, structureNodes " +
				"OPTIONAL MATCH u-[r:TEACHES]->(s:Subject) WITH COLLECT(distinct s.code) as subjectCodes, admStruct, admGroups, parents, children, functions, u, structureNodes " +
				getMgroups;
		if(filterNullReturn){
			query += "RETURN DISTINCT u.profiles as type, structureNodes, " +
					"filter(x IN functions WHERE filter(y IN x WHERE y IS NOT NULL)) as functions, u.functions as aafFunctions," +
					"CASE WHEN children IS NULL THEN [] ELSE filter(x IN children WHERE x.id IS NOT NULL) END as children, " +
					"CASE WHEN parents IS NULL THEN [] ELSE filter(x IN parents WHERE x.id IS NOT NULL) END as parents, " +
					"CASE WHEN admGroups IS NULL THEN [] ELSE filter(x IN admGroups WHERE x.id IS NOT NULL) END as functionalGroups, " +
					"CASE WHEN admStruct IS NULL THEN [] ELSE filter(x IN admStruct WHERE x.id IS NOT NULL) END as administrativeStructures, " +
					"CASE WHEN subjectCodes IS NULL THEN [] ELSE filter(x IN subjectCodes WHERE x IS NOT NULL) END as subjectCodes, ";
		} else {
			query += "RETURN DISTINCT u.profiles as type, structureNodes, functions, " +
					"CASE WHEN children IS NULL THEN [] ELSE children END as children, " +
					"CASE WHEN parents IS NULL THEN [] ELSE parents END as parents, " +
					"CASE WHEN admGroups IS NULL THEN [] ELSE admGroups END as functionalGroups, " +
					"CASE WHEN admStruct IS NULL THEN [] ELSE admStruct END as administrativeStructures, " +
					"CASE WHEN subjectCodes IS NULL THEN [] ELSE subjectCodes END as subjectCodes, ";
		}

		query += resultMgroups +
				"u";
		final Handler<Either<String, JsonObject>> filterResultHandler = event -> {
			if (event.isRight()) {
				final JsonObject r = event.right().getValue();
				filterAttributes.add("password").add("resetCode").add("lastNameSearchField").add("firstNameSearchField")
						.add("displayNameSearchField").add("checksum").add("emailSearchField")
						.add("emailInternal").add("resetDate").add("lastScheme").add("lastDomain");
				for (Object o : filterAttributes) {
					r.remove((String) o);
				}

				//put administrative attachment first in structureNodes
				final JsonArray jaAdm = r.getJsonArray("administrativeStructures");
				if (jaAdm != null && !jaAdm.isEmpty()) {
					final JsonObject jAdm = jaAdm.getJsonObject(0);
					if (jAdm!=null) {
						final String idAdm = StringUtils.trimToBlank(jAdm.getString("id"));
						if (r.getJsonArray("structureNodes") != null && !r.getJsonArray("structureNodes").isEmpty()) {
							final JsonArray newJaStruct = new JsonArray();
							for (Object o : r.getJsonArray("structureNodes")) {
								if (o == null || !(o instanceof JsonObject)) continue;
								if (idAdm.equals(((JsonObject) o).getString("id", ""))) {
									newJaStruct.add((JsonObject) o);
									break;
								}
							}
							for (Object o : r.getJsonArray("structureNodes")) {
								if (o == null || !(o instanceof JsonObject)) continue;
								if (!idAdm.equals(((JsonObject) o).getString("id", ""))) {
									newJaStruct.add((JsonObject) o);
								}
							}

							r.put("structureNodes", newJaStruct);
						}
					}
				}
				if(r.containsKey("aafFunctions")) {
					extractReformatUserFunctions(r);
				}
			}
			result.handle(event);
		};
		neo.execute(query, new JsonObject().put("id", id), fullNodeMergeHandler("u", filterResultHandler, "structureNodes"));
	}

	private void extractReformatUserFunctions(JsonObject r) {
		//reformat functions
		JsonObject functions = new JsonObject();
		for (Object o : getOrElse(r.getJsonArray("aafFunctions"), new fr.wseduc.webutils.collections.JsonArray())) {
			if (o == null) continue;
			String[] sf = o.toString().split("\\$");
			if (sf.length == 5) {
				JsonObject jo = functions.getJsonObject(sf[1]);
				if (jo == null) {
					jo = new JsonObject().put("code", sf[1])
							.put("functionName", sf[2])
							.put("scope", new fr.wseduc.webutils.collections.JsonArray())
							.put("structureExternalIds", new fr.wseduc.webutils.collections.JsonArray())
							.put("subjects", new JsonObject());
					functions.put(sf[1], jo);
				}
				JsonObject subject = jo.getJsonObject("subjects").getJsonObject(sf[3]);
				if (subject == null) {
					subject = new JsonObject()
							.put("subjectCode", sf[3])
							.put("subjectName", sf[4])
							.put("scope", new fr.wseduc.webutils.collections.JsonArray())
							.put("structureExternalIds", new fr.wseduc.webutils.collections.JsonArray());
					jo.getJsonObject("subjects").put(sf[3], subject);
				}
				jo.getJsonArray("structureExternalIds").add(sf[0]);
				subject.getJsonArray("structureExternalIds").add(sf[0]);
			}
		}
		r.remove("aafFunctions");
		for (Object o : getOrElse(r.getJsonArray("functions"), new fr.wseduc.webutils.collections.JsonArray())) {
			if (!(o instanceof JsonArray)) continue;
			JsonArray a = (JsonArray) o;
			String code = a.getString(0);
			if (code != null) {
				functions.put(code, new JsonObject()
						.put("code", code)
						.put("scope", a.getJsonArray(1))
				);
			}
		}
		r.put("functions", functions);
	}

    @Override
    public void getClasses(String id, Handler<Either<String, JsonObject>> handler) {
        final StringBuilder query = new StringBuilder();
        query.append("MATCH (user:User {id: {userId} }) ");
        query.append("OPTIONAL MATCH (user)-[:IN]->(:ProfileGroup)-[:DEPENDS]->(clazz:Class)-[:BELONGS]->(struct1:Structure) ");
        query.append("OPTIONAL MATCH (user)-[:IN]->(:Group)-[:DEPENDS]->(struct2:Structure) ");
        query.append("WITH struct1, struct2, COLLECT(DISTINCT {name: clazz.name, id: clazz.id}) as classes ");
        query.append("WITH COLLECT(DISTINCT {name: struct1.name, id: struct1.id, classes: classes}) as schools1,");
        query.append("COLLECT(DISTINCT {name: struct2.name, id: struct2.id}) as schools2 ");
        query.append("RETURN (schools1 + schools2) AS schools");
        final Map<String, Object> params = new HashMap<>();
        params.put("userId", id);
        neo.execute(query.toString(), params, validUniqueResultHandler(res-> {
            if (res.isRight()) {
                final JsonObject results = res.right().getValue();
                handler.handle(new Either.Right<>(results));
            } else {
                handler.handle(new Either.Left<>(res.left().getValue()));
            }
        }));
	}


	@Override
    public void getGroups(String id, Handler<Either<String, JsonArray>> results) {
        String query = ""
				+ "MATCH (g:Group)<-[:IN]-(u:User { id: {id} }) WHERE exists(g.id) "
				+ "OPTIONAL MATCH (sg:Structure)<-[:DEPENDS]-(g) "
				+ "OPTIONAL MATCH (sc:Structure)<-[:BELONGS]-(c:Class)<-[:DEPENDS]-(g) "
				+ "WITH COALESCE(sg, sc) as s, c, g "
				+ "WITH s, c, g, "
				+ "collect( distinct {name: c.name, id: c.id}) as classes, "
				+ "collect( distinct {name: s.name, id: s.id}) as structures, "
				+ "HEAD(filter(x IN labels(g) WHERE x <> 'Visible' AND x <> 'Group')) as type "
				+ "RETURN DISTINCT "
				+ "g.id as id, "
				+ "g.name as name, "
				+ "g.filter as filter, "
				+ "g.displayName as displayName, "
				+ "g.users as internalCommunicationRule, "
				+ "type, "
				+ "CASE WHEN any(x in classes where x <> {name: null, id: null}) THEN classes END as classes, "
				+ "CASE WHEN any(x in structures where x <> {name: null, id: null}) THEN structures END as structures, "
				+ "CASE WHEN (g: ProfileGroup)-[:DEPENDS]->(:Structure) THEN 'StructureGroup' "
				+ "     WHEN (g: ProfileGroup)-[:DEPENDS]->(:Class) THEN 'ClassGroup' "
				+ "     WHEN HAS(g.subType) THEN g.subType END as subType";
			JsonObject params = new JsonObject().put("id", id);
        neo.execute(query, params, validResultHandler(results));
    }

	@Override
	public void list(String structureId, String classId, JsonArray expectedProfiles,
			Handler<Either<String, JsonArray>> results) {
		JsonObject params = new JsonObject();
		String filterProfile = "";
		String filterStructure = "";
		String filterClass = "";
		if (expectedProfiles != null && expectedProfiles.size() > 0) {
			filterProfile = "WHERE p.name IN {expectedProfiles} ";
			params.put("expectedProfiles", expectedProfiles);
		}
		if (classId != null && !classId.trim().isEmpty()) {
			filterClass = "(g:ProfileGroup)-[:DEPENDS]->(n:Class {id : {classId}}), ";
			params.put("classId", classId);
		} else if (structureId != null && !structureId.trim().isEmpty()) {
			filterStructure = "(pg:ProfileGroup)-[:DEPENDS]->(n:Structure {id : {structureId}}), ";
			params.put("structureId", structureId);
		}
		String query =
				"MATCH " +filterClass + filterStructure +
				"(u:User)-[:IN]->g-[:DEPENDS*0..1]->pg-[:HAS_PROFILE]->(p:Profile) " +
				filterProfile +
				"RETURN DISTINCT u.id as id, p.name as type, u.externalId as externalId, u.IDPN as IDPN, " +
				"u.activationCode as code, u.login as login, u.firstName as firstName, " +
				"u.lastName as lastName, u.displayName as displayName " +
				"ORDER BY type DESC, displayName ASC ";
		neo.execute(query, params, validResultHandler(results));
	}

	@Override
	public void listIsolated(
			String structureId, 
			List<String> profile, 
			String sortOn,
			final Integer fromIndex,
			final Integer limitResult,
			final String searchType,
			String searchTerm,
			Handler<Either<String, JsonArray>> results) {
		JsonObject params = new JsonObject();
		String query;

		String condition = "";
		searchTerm = normalize( searchTerm );
		if(searchTerm != null && searchTerm.length()>0 ){
			if ("email".equals(searchType)) {
				condition += "AND u.emailSearchField CONTAINS {searchTerm} ";
			} else {
				condition += "AND u.displayNameSearchField CONTAINS {searchTerm} ";
				// Remove accents when searching for a display name.
				searchTerm = StringUtils.stripAccents(searchTerm);
			}
			params.put("searchTerm", searchTerm);
		}

		// users without class
		if (structureId != null && !structureId.trim().isEmpty()) {
			query = "MATCH  (s:Structure { id : {structureId}})<-[:DEPENDS]-(g:ProfileGroup)<-[:IN]-(u:User), " +
					"g-[:HAS_PROFILE]->(p:Profile) " +
					"WHERE  NOT(u-[:IN]->()-[:DEPENDS]->(:Class)-[:BELONGS]->s) "+ condition;
			params.put("structureId", structureId);
			if (profile != null && !profile.isEmpty()) {
				query += "AND p.name IN {profile} ";
				params.put("profile", new fr.wseduc.webutils.collections.JsonArray(profile));
			}
		} else { // users without structure
			query = "MATCH (u:User)" +
					"WHERE NOT(u-[:IN]->()-[:DEPENDS]->(:Structure)) "+ condition +
					"OPTIONAL MATCH u-[:IN]->(dpg:DefaultProfileGroup)-[:HAS_PROFILE]->(p:Profile) ";
		}
		
		query += "RETURN DISTINCT u.id as id, p.name as type, " +
				"u.activationCode as code, u.firstName as firstName," +
				"u.lastName as lastName, u.displayName as displayName ";

		// Apply search parameters and sort order
		if( sortOn==null || sortOn.length()<2 ) {
			// Default sort order, historical behaviour.
			query += "ORDER BY type DESC, displayName ASC ";
		} else {
			final String order = sortOn.startsWith("-") ? "DESC" : "ASC";
			if( sortOn.charAt(0)=='+'|| sortOn.charAt(0)=='-' ) {
				sortOn = sortOn.substring(1);
			}
			query += "ORDER BY "+ sortOn +" "+ order;
		}
		if( fromIndex != null && fromIndex.intValue() > 0 ) {
			query += " SKIP {skip}";
			params.put( "skip", fromIndex );
		}
		if( limitResult != null && limitResult.intValue() > 0 ) {
			query += " LIMIT {limit}";
			params.put( "limit", limitResult );
		}
		neo.execute(query, params, validResultHandler(results));
	}

	@Override
	public void listAdmin(String structureId, boolean includeSubStructure, String classId, String groupId,
						  JsonArray expectedProfiles, UserInfos userInfos, io.vertx.core.Handler<fr.wseduc.webutils.Either<String,JsonArray>> results) {
		listAdmin(structureId, includeSubStructure, classId, groupId, expectedProfiles, null, null, null, userInfos, results);
	};

	@Override
	public void listAdmin(String structureId, boolean includeSubStructure, String classId, String groupId,
						  JsonArray expectedProfiles, String filterActivated, String searchTerm, String searchType,
						  UserInfos userInfos, Handler<Either<String, JsonArray>> results) {
		JsonObject params = new JsonObject();
		String filter = "";
		String filterProfile = "WHERE 1=1 ";
		String optionalMatch =
			"OPTIONAL MATCH u-[:IN]->(:ProfileGroup)-[:DEPENDS]->(class:Class)-[:BELONGS]->(s) " +
			"OPTIONAL MATCH u-[:RELATED]->(parent: User) " +
			"OPTIONAL MATCH (child: User)-[:RELATED]->u " +
			"OPTIONAL MATCH u-[rf:HAS_FUNCTION]->fg-[:CONTAINS_FUNCTION*0..1]->(f:Function) " +
			"OPTIONAL MATCH u-[:TEACHES]->(sub:Subject) ";
		if (expectedProfiles != null && expectedProfiles.size() > 0) {
			filterProfile += "AND p.name IN {expectedProfiles} ";
			params.put("expectedProfiles", expectedProfiles);
		}
		if (classId != null && !classId.trim().isEmpty()) {
			filter = "(n:Class {id : {classId}})<-[:DEPENDS]-(g:ProfileGroup)<-[:IN]-";
			params.put("classId", classId);
		} else if (structureId != null && !structureId.trim().isEmpty()) {
			filter = "(n:Structure {id : {structureId}})" + (includeSubStructure ? "<-[:HAS_ATTACHMENT*0..]-(:Structure)" : "") +
					"<-[:DEPENDS]-(g:ProfileGroup)<-[:IN]-";
			params.put("structureId", structureId);
		} else if (groupId != null && !groupId.trim().isEmpty()) {
			filter = "(n:Group {id : {groupId}})<-[:IN]-";
			params.put("groupId", groupId);
		}
		String condition = "";
		String functionMatch = "WITH u MATCH (s:Structure)<-[:DEPENDS]-(pg:ProfileGroup)-[:HAS_PROFILE]->(p:Profile), u-[:IN]->pg ";
		if (!userInfos.getFunctions().containsKey(SUPER_ADMIN) &&
				!userInfos.getFunctions().containsKey(ADMIN_LOCAL) &&
				!userInfos.getFunctions().containsKey(CLASS_ADMIN)) {
			results.handle(new Either.Left<String, JsonArray>("forbidden"));
			return;
		} else if (userInfos.getFunctions().containsKey(ADMIN_LOCAL)) {
			UserInfos.Function f = userInfos.getFunctions().get(ADMIN_LOCAL);
			List<String> scope = f.getScope();
			if (scope != null && !scope.isEmpty()) {
				condition = "AND s.id IN {scope} ";
				params.put("scope", new fr.wseduc.webutils.collections.JsonArray(scope));
			}
		} else if(userInfos.getFunctions().containsKey(CLASS_ADMIN)){
			UserInfos.Function f = userInfos.getFunctions().get(CLASS_ADMIN);
			List<String> scope = f.getScope();
			if (scope != null && !scope.isEmpty()) {
				functionMatch = "WITH u MATCH (c:Class)<-[:DEPENDS]-(cpg:ProfileGroup)-[:DEPENDS]->(pg:ProfileGroup)-[:HAS_PROFILE]->(p:Profile), u-[:IN]->pg ";
				condition = "AND c.id IN {scope} ";
				params.put("scope", new fr.wseduc.webutils.collections.JsonArray(scope));
			}
		}
		searchTerm = normalize( searchTerm );
		if(searchTerm != null){
			if ("email".equals(searchType)) {
				condition += "AND u.emailSearchField CONTAINS {searchTerm} ";
			} else {
				condition += "AND u.displayNameSearchField CONTAINS {searchTerm} ";
				// Remove accents when searching for a display name.
				searchTerm = StringUtils.stripAccents(searchTerm);
			}
			params.put("searchTerm", searchTerm);
		}
		if(filterActivated != null){
			if("inactive".equals(filterActivated)){
				condition += "AND NOT(u.activationCode IS NULL)  ";
			} else if("active".equals(filterActivated)){
				condition += "AND u.activationCode IS NULL ";
			}
		}
		if (!userInfos.getFunctions().containsKey(SUPER_ADMIN)) {
			condition += "AND " + DefaultSchoolService.EXCLUDE_ADMC_QUERY_FILTER;
		}

		String query =
				"MATCH " + filter + "(u:User) " +
				functionMatch + filterProfile + condition + optionalMatch +
				"RETURN DISTINCT u.id as id, p.name as type, u.externalId as externalId, " +
				"u.activationCode as code, " +
				"CASE WHEN u.loginAlias IS NOT NULL THEN u.loginAlias ELSE u.login END as login, " +
				"u.login as originalLogin, " +
				"u.firstName as firstName, " +
				"u.lastName as lastName, u.displayName as displayName, u.source as source, u.attachmentId as attachmentId, " +
				"u.birthDate as birthDate, u.blocked as blocked, u.created as creationDate, u.lastLogin as lastLogin, " +
				"u.email as email, u.homePhone as phone, u.mobile as mobile, u.zipCode as zipCode, u.address as address, " +
				"u.city as city, u.country as country, " +
				"extract(function IN u.functions | last(split(function, \"$\"))) as aafFunctions, " +
				"collect(distinct {id: s.id, name: s.name}) as structures, " +
				"collect(distinct {id: class.id, name: class.name}) as allClasses, " +
				"collect(distinct [f.externalId, rf.scope]) as functions, " +
				"CASE WHEN parent IS NULL THEN [] ELSE collect(distinct {id: parent.id, firstName: parent.firstName, lastName: parent.lastName}) END as parents, " +
				"CASE WHEN child IS NULL THEN [] ELSE collect(distinct {id: child.id, firstName: child.firstName, lastName: child.lastName, attachmentId : child.attachmentId, childExternalId : child.externalId, displayName : child.displayName }) END as children, " +
				"HEAD(COLLECT(distinct parent.externalId)) as parent1ExternalId, " + // Hack for GEPI export
				"HEAD(TAIL(COLLECT(distinct parent.externalId))) as parent2ExternalId, " + // Hack for GEPI export
				"COUNT(distinct class.id) > 0 as hasClass, " + // Hack for Esidoc export
				"CASE WHEN p.name = 'Teacher' THEN 'PROFS' ELSE 'ELEVES' END as chamiloProfile, " + // Hack for chamilo export
				"CASE WHEN p.name = 'Teacher' THEN collect(distinct {name: sub.label}) ELSE collect(distinct {name: class.name}) END as allClassesSubject, " + // Hack for chamilo export
				"split(u.birthDate, '-')[0] as birthYear, " + // Hack for Pmb export
				"REPLACE(u.address,';',' ') as safeAddress " + // Hack for Pmb export
				"ORDER BY type DESC, displayName ASC ";

		neo.execute(query, params, validResultHandler(results));
	}

	
	private String normalize(String str) {
		if (str != null ) {
			str = str.toLowerCase().replaceAll("\\s+", "").trim();
			if( str.isEmpty() ) {
				return null;
			}
		}
		return str;
	}

	@Override
	public void delete(List<String> users, Handler<Either<String, JsonObject>> result) {
		JsonObject action = new JsonObject()
				.put("action", "manual-delete-user")
				.put("users", new fr.wseduc.webutils.collections.JsonArray(users));
		eb.send(Directory.FEEDER, action, handlerToAsyncHandler(validEmptyHandler(result)));
	}

	@Override
	public void restore(List<String> users, Handler<Either<String, JsonObject>> result) {
		JsonObject action = new JsonObject()
				.put("action", "manual-restore-user")
				.put("users", new fr.wseduc.webutils.collections.JsonArray(users));
		eb.send(Directory.FEEDER, action, handlerToAsyncHandler(validEmptyHandler(result)));
	}

	@Override
	public void addFunction(String id, String functionCode, JsonArray scope, String inherit,
			Handler<Either<String, JsonObject>> result) {
		JsonObject action = new JsonObject()
				.put("action", "manual-add-user-function")
				.put("userId", id)
				.put("function", functionCode)
				.put("inherit", inherit)
				.put("scope", scope);
		eb.send(Directory.FEEDER, action, ar -> {
			if (ar.succeeded()) {
				JsonArray res = ((JsonObject) ar.result().body()).getJsonArray("results");
				JsonObject json = new JsonObject();
				if (res.size() == 4) {
					JsonArray r = res.getJsonArray(1);
					if (r.size() == 1) {
						json = r.getJsonObject(0);
					}
				}
				result.handle(new Either.Right<>(json));
			} else {
				result.handle(new Either.Left<>(ar.cause().getMessage()));
			}
		});
	}

	@Override
	public void addHeadTeacherManual(String id,String structureExternalId, String classExternalId,
							Handler<Either<String, JsonObject>> result) {
		JsonObject action = new JsonObject()
				.put("action", "manual-add-head-teacher")
				.put("userId", id)
				.put("classExternalId", classExternalId)
				.put("structureExternalId", structureExternalId);
		eb.send(Directory.FEEDER, action,handlerToAsyncHandler(validEmptyHandler(result)));
	}

	@Override
	public void updateHeadTeacherManual(String id,String structureExternalId, String classExternalId, Handler<Either<String, JsonObject>> result) {
		JsonObject action = new JsonObject()
				.put("action", "manual-update-head-teacher")
				.put("userId", id)
				.put("classExternalId", classExternalId)
				.put("structureExternalId", structureExternalId);
		eb.send(Directory.FEEDER, action, handlerToAsyncHandler(validEmptyHandler(result)));
	}

	@Override
	public void addDirectionManual(String id,String structureExternalId, Handler<Either<String, JsonObject>> result) {
		JsonObject action = new JsonObject()
				.put("action", "manual-add-direction")
				.put("userId", id)
				.put("structureExternalId", structureExternalId);
		eb.send(Directory.FEEDER, action,handlerToAsyncHandler(validEmptyHandler(result)));
	}

	@Override
	public void removeDirectionManual(String id,String structureExternalId, Handler<Either<String, JsonObject>> result) {
		JsonObject action = new JsonObject()
				.put("action", "manual-remove-direction")
				.put("userId", id)
				.put("structureExternalId", structureExternalId);
		eb.send(Directory.FEEDER, action, handlerToAsyncHandler(validEmptyHandler(result)));
	}

	@Override
	public void removeFunction(String id, String functionCode, Handler<Either<String, JsonObject>> result) {
		JsonObject action = new JsonObject()
				.put("action", "manual-remove-user-function")
				.put("userId", id)
				.put("function", functionCode);
		eb.send(Directory.FEEDER, action, handlerToAsyncHandler(validEmptyHandler(result)));
	}

	public void listFunctions(String userId, Handler<Either<String, JsonArray>> result) {
		String query =
				"MATCH (u:User{id: {userId}})-[rf:HAS_FUNCTION]->fg-[:CONTAINS_FUNCTION*0..1]->(f:Function) " +
				"RETURN COLLECT(distinct [f.externalId, rf.scope]) as functions";
		JsonObject params = new JsonObject();
		params.put("userId", userId);
		neo.execute(query, params, validResultHandler(result));
	}

	@Override
	public void addGroup(String id, String groupId, Handler<Either<String, JsonObject>> result) {
		JsonObject action = new JsonObject()
				.put("action", "manual-add-user-group")
				.put("userId", id)
				.put("groupId", groupId);
		eb.send(Directory.FEEDER, action, handlerToAsyncHandler(validEmptyHandler(result)));
	}

	@Override
	public void removeGroup(String id, String groupId, Handler<Either<String, JsonObject>> result) {
		JsonObject action = new JsonObject()
				.put("action", "manual-remove-user-group")
				.put("userId", id)
				.put("groupId", groupId);
		eb.send(Directory.FEEDER, action, handlerToAsyncHandler(validEmptyHandler(result)));
	}

	@Override
	public void listAdml(String scopeId, Handler<Either<String, JsonArray>> result) {
		String query =
				"MATCH (n)<-[:DEPENDS]-(g:FunctionGroup)<-[:IN]-(u:User) " +
				"WHERE (n:Structure OR n:Class) AND n.id = {scopeId} AND g.name =~ '^.*-AdminLocal$' " +
				"OPTIONAL MATCH u-[:IN]->(pg:ProfileGroup)-[:HAS_PROFILE]->(profile:Profile) " +
				"RETURN distinct u.id as id, u.login as login," +
				" u.displayName as username, profile.name as type " +
				"ORDER BY username ";
		JsonObject params = new JsonObject();
		params.put("scopeId", scopeId);
		neo.execute(query, params, validResultHandler(result));
	}

	@Override
	public void getInfos(String userId, Handler<Either<String, JsonObject>> result) {
		String query =
				"MATCH (n:User {id : {id}}) " +
				"OPTIONAL MATCH n-[:IN]->(gp:Group) " +
				"OPTIONAL MATCH n-[:IN]->(:ProfileGroup)-[:DEPENDS]->(s:Structure) " +
				"OPTIONAL MATCH n-[:IN]->()-[:DEPENDS]->(c:Class) " +
				"OPTIONAL MATCH n-[rf:HAS_FUNCTION]->fg-[:CONTAINS_FUNCTION*0..1]->(f:Function) " +
				"OPTIONAL MATCH n-[:IN]->()-[:HAS_PROFILE]->(p:Profile) " +
				"OPTIONAL MATCH n-[:ADMINISTRATIVE_ATTACHMENT]->(sa:Structure) " +
				"RETURN distinct " +
				"n, n.functions as ufunctions, COLLECT(distinct c) as classes, HEAD(COLLECT(distinct p.name)) as type, " +
				"COLLECT(distinct s) as structures, COLLECT(distinct [f.externalId, rf.scope]) as functions, " +
				"COLLECT(distinct gp) as groups, COLLECT(distinct sa) as administratives";
		neo.execute(query, new JsonObject().put("id", userId),
				fullNodeMergeHandler("n", result, "structures", "classes","groups", "administratives"));
	}

	@Override
	public void relativeStudent(String relativeId, String studentId, Handler<Either<String, JsonObject>> eitherHandler) {
		JsonObject action = new JsonObject()
				.put("action", "manual-relative-student")
				.put("relativeId", relativeId)
				.put("studentId", studentId);
		eb.send(Directory.FEEDER, action, handlerToAsyncHandler(validUniqueResultHandler(0, eitherHandler)));
	}

	@Override
	public void unlinkRelativeStudent(String relativeId, String studentId, Handler<Either<String, JsonObject>> eitherHandler) {
		JsonObject action = new JsonObject()
				.put("action", "manual-unlink-relative-student")
				.put("relativeId", relativeId)
				.put("studentId", studentId);
		eb.send(Directory.FEEDER, action, handlerToAsyncHandler(validEmptyHandler(eitherHandler)));
	}

	@Override
	public void ignoreDuplicate(String userId1, String userId2, Handler<Either<String, JsonObject>> result) {
		JsonObject action = new JsonObject()
				.put("action", "ignore-duplicate")
				.put("userId1", userId1)
				.put("userId2", userId2);
		eb.send(Directory.FEEDER, action, handlerToAsyncHandler(validEmptyHandler(result)));
	}

	@Override
	public void listDuplicates(JsonArray structures, boolean inherit, Handler<Either<String, JsonArray>> results) {
		JsonObject action = new JsonObject()
				.put("action", "list-duplicate")
				.put("structures", structures)
				.put("inherit", inherit);
		eb.send(Directory.FEEDER, action, new DeliveryOptions().setSendTimeout(600000l), handlerToAsyncHandler(validResultHandler(results)));
	}

	@Override
	public void mergeDuplicate(String userId1, String userId2, Handler<Either<String, JsonObject>> handler) {
		JsonObject action = new JsonObject()
				.put("action", "merge-duplicate")
				.put("userId1", userId1)
				.put("userId2", userId2);
		eb.send(Directory.FEEDER, action, handlerToAsyncHandler(validEmptyHandler(handler)));
	}

	@Override
	public void listByUAI(List<String> UAI, JsonArray expectedTypes, boolean isExportFull, JsonArray fields, Handler<Either<String, JsonArray>> results) {
		if (UAI == null || UAI.isEmpty()) {
			results.handle(new Either.Left<String, JsonArray>("missing.uai"));
			return;
		} else {
			for (String uaiCode: UAI) {
				if (!StringValidation.isUAI(uaiCode)) {
					results.handle(new Either.Left<String, JsonArray>("invalid.uai"));
					return;
				}
			}
		}

		if (fields == null || fields.size() == 0) {
			fields = new fr.wseduc.webutils.collections.JsonArray().add("id").add("externalId").add("lastName").add("firstName").add("login");
		}

		//user's fields for Full Export
		if(isExportFull){
			fields.add("email");
			fields.add("emailAcademy");
			fields.add("mobile");
			fields.add("deleteDate");
			fields.add("functions");
			fields.add("displayName");
		}

		// Init params and filter for all type of queries
		String  filter =  "WHERE s.UAI IN {uai} ";

		JsonObject params = new JsonObject().put("uai", new fr.wseduc.webutils.collections.JsonArray(UAI));

		StringBuilder query = new StringBuilder();
		query.append("MATCH (s:Structure)<-[:DEPENDS]-(cpg:ProfileGroup)");

		// filter by types if needed OR full export
		if( isExportFull || (expectedTypes != null && expectedTypes.size() > 0)) {
			query.append("-[:HAS_PROFILE]->(p:Profile)");
		}
		// filter by types if needed
		if (expectedTypes != null && expectedTypes.size() > 0) {

			filter += "AND p.name IN {expectedTypes} ";
			params.put("expectedTypes", expectedTypes);
		}

		query.append(", cpg<-[:IN]-(u:User) ")
				.append(filter);

		if (fields.contains("administrativeStructure")) {
			query.append("OPTIONAL MATCH u-[:ADMINISTRATIVE_ATTACHMENT]->sa ");
		}

		query.append("RETURN DISTINCT ");

		for (Object field : fields) {
			if ("type".equals(field) || "profile".equals(field)) {
				query.append(" HEAD(u.profiles)");
			} else if ("administrativeStructure".equals(field)) {
				query.append(" sa.externalId ");
			} else {
				query.append(" u.").append(field);
			}
			query.append(" as ").append(field).append(",");
		}
		query.deleteCharAt(query.length() - 1);

		//Full Export : profiles and Structure
		if(isExportFull){
			query.append(", p.name as profiles");
			query.append(", s.externalId as structures")
					.append(" , CASE WHEN size(u.classes) > 0  THEN  last(collect(u.classes)) END as classes");
		}

		neo.execute(query.toString(), params, validResultHandler(results));
	}

	@Override
	public void generateMergeKey(String userId, Handler<Either<String, JsonObject>> handler) {
		if (Utils.defaultValidationParamsError(handler, userId)) return;
		final String query = "MATCH (u:User {id: {id}}) SET u.mergeKey = {mergeKey} return u.mergeKey as mergeKey";
		final JsonObject params = new JsonObject().put("id", userId).put("mergeKey", UUID.randomUUID().toString());
		neo.execute(query, params, validUniqueResultHandler(handler));
	}

	@Override
	public void mergeByKey(String userId, JsonObject body, Handler<Either<String, JsonObject>> handler) {
		if (Utils.defaultValidationParamsNull(handler, userId, body)) return;
		JsonObject action = new JsonObject()
				.put("action", "merge-by-keys")
				.put("originalUserId", userId)
				.put("mergeKeys", body.getJsonArray("mergeKeys"));
		eb.send(Directory.FEEDER, action, handlerToAsyncHandler(validUniqueResultHandler(5, handler)));
	}

	@Override
	public void unmergeByLogins(JsonObject body, Handler<Either<String, JsonObject>> handler) {
		if (Utils.defaultValidationParamsNull(handler, body)) return;
		JsonObject action = new JsonObject()
				.put("action", "unmerge-by-logins")
				.put("originalUserId", body.getString("originalUserId"))
				.put("mergedLogins", body.getJsonArray("mergedLogins"));
		eb.send(Directory.FEEDER, action, handlerToAsyncHandler(validUniqueResultHandler(handler)));
	}

	@Override
	public void listChildren(String userId, Handler<Either<String, JsonArray>> handler) {
		final String query =
				"MATCH (n:User {id : {id}})<-[:RELATED]-(child:User)-[:IN]->(:ProfileGroup)-[:DEPENDS]->(s:Structure) " +
				"OPTIONAL MATCH (child)-[:IN]->(:ProfileGroup)-[:DEPENDS]->(c:Class) " +
				"WITH COLLECT(distinct c.name) as classesNames, s, child " +
				"RETURN s.name as structureName, COLLECT(distinct {id: child.id, displayName: child.displayName, externalId: child.externalId, classesNames : classesNames}) as children ";
		final JsonObject params = new JsonObject().put("id", userId);
		neo.execute(query, params, validResultHandler(handler));
	}

	@Override
	public void list(String groupId, boolean itSelf, String userId,
			final Handler<Either<String, JsonArray>> handler) {
		String condition = (itSelf || userId == null) ? "" : "AND u.id <> {userId} ";
		String query =
				"MATCH (n:Group)<-[:IN]-(u:User) " +
				"WHERE n.id = {groupId} " + condition +
				"OPTIONAL MATCH (n)-[:DEPENDS*0..1]->(:ProfileGroup)-[:HAS_PROFILE]->(profile:Profile) " +
				"OPTIONAL MATCH (u)-[:IN]->(pg:ProfileGroup)-[:DEPENDS]->(s:Structure) " +
				"OPTIONAL MATCH (pg)-[:HAS_PROFILE]->(pro:Profile) " +
				"RETURN distinct u.id as id, u.login as login," +
				"u.displayName as username, u.firstName as firstName, u.lastName as lastName, profile.name as type," +
				"CASE WHEN s IS NULL THEN [] ELSE COLLECT(DISTINCT {id: s.id, name: s.name}) END as structures," +
				"CASE WHEN pro IS NULL THEN NULL ELSE HEAD(COLLECT(DISTINCT pro.name)) END as profile " +
				"ORDER BY username ";
		JsonObject params = new JsonObject();
		params.put("groupId", groupId);
		if (!itSelf && userId != null) {
			params.put("userId", userId);
		}
		neo.execute(query, params, validResultHandler(handler));
	}

	@Override
	public void list(JsonArray groupIds, JsonArray userIds, boolean itSelf, String userId,
			final Handler<Either<String, JsonArray>> handler) {
		String condition = (itSelf || userId == null) ? "" : "AND u.id <> {userId} ";
		String query =
				"MATCH (n:Group)<-[:IN]-(u:User) " +
				"WHERE n.id IN {groupIds} " + condition +
				"OPTIONAL MATCH n-[:DEPENDS*0..1]->(pg:ProfileGroup)-[:HAS_PROFILE]->(profile:Profile) " +
				"RETURN distinct u.id as id, u.login as login," +
				" u.displayName as username, profile.name as type " +
				"ORDER BY username " +
				"UNION " +
				"MATCH (u:User) " +
				"WHERE u.id IN {userIds} " + condition +
				"OPTIONAL MATCH u-[:IN]->(pg:ProfileGroup)-[:HAS_PROFILE]->(profile:Profile) " +
				"RETURN distinct u.id as id, u.login as login," +
				" u.displayName as username, profile.name as type " +
				"ORDER BY username ";
		JsonObject params = new JsonObject();
		params.put("groupIds", groupIds);
		params.put("userIds", userIds);
		if (!itSelf && userId != null) {
			params.put("userId", userId);
		}
		neo.execute(query, params, validResultHandler(handler));
	}

	@Override
	public void getUserInfos(String userId, final Handler<Either<String,JsonObject>> handler) {
		String query = "MATCH (u:`User` { id : {userId}}) " +
				"OPTIONAL MATCH u-[:USERBOOK]->(ub: UserBook) WITH ub.motto as motto, ub.health as health, ub.mood as mood, u,  "+
				UserBookService.selectHobbies(userBookData, "ub")+
				"OPTIONAL MATCH s<-[:BELONGS]-(c:Class)<-[:DEPENDS]-(cpg:ProfileGroup)-[:DEPENDS]->(spg:ProfileGroup)-[:HAS_PROFILE]->(Profile), cpg<-[:IN]-u-[:IN]->spg WITH s, COLLECT(distinct {name: c.name, id: c.id}) as c, motto, health, mood, hobbies, u " +
				"WITH COLLECT(distinct {name: s.name, id: s.id, classes: c, source: s.source}) as schools, motto, health, mood, hobbies, u " +
				"OPTIONAL MATCH u-[:RELATED]-(u2: User) WITH COLLECT(distinct {relatedName: u2.displayName, relatedId: u2.id, relatedType: u2.profiles}) as relativeList, schools, motto, health, mood, hobbies, u " +
				"RETURN DISTINCT u.profiles as profiles, u.id as id, u.firstName as firstName, u.lastName as lastName, u.displayName as displayName, "+
				"u.email as email, u.homePhone as homePhone, u.mobile as mobile, u.birthDate as birthDate, u.login as originalLogin, relativeList, " +
				"motto, health, mood, hobbies, " +
				"CASE WHEN schools IS NULL THEN [] ELSE schools END as schools ";
		JsonObject params = new JsonObject();
		params.put("userId", userId);
		neo.execute(query, params, validUniqueResultHandler(res->{
			if(res.isRight()){
				final JsonObject result = res.right().getValue();
				result.put("hobbies", UserBookService.extractHobbies(userBookData, result, true));
				handler.handle(new Either.Right<>(result));
			}else{
				handler.handle(res);
			}
		}));
	}

	@Override
	public void listByLevel(String levelContains, String levelNotContains, String profile, String structureId, boolean stream,
			Handler<Either<String, JsonArray>> handler) {
		final JsonObject params = new JsonObject();
		params.put("level", levelContains);
		String levelFilter = "";
		String structureMatcher = "";
		if (isNotEmpty(levelNotContains)) {
			levelFilter = "AND NOT(u.level contains {notLevel}) ";
			params.put("notLevel", levelNotContains);
		}
		if(isNotEmpty(structureId)) {
			structureMatcher = "-[:IN]->(pg:ProfileGroup)-[:DEPENDS]->(s:Structure {id:{structureId}})";
			params.put("structureId", structureId);
		}

		String query;
		if ("Student".equals(profile)) {
			query = "MATCH (u:User)" + structureMatcher + " " +
					"WHERE has(u.password) and u.level contains {level} " + levelFilter +
					"RETURN u.id as id, u.ine as ine, head(u.profiles) as profile, u.lastName as lastName, u.firstName as firstName, " +
					"u.login as login, u.loginAlias as loginAlias, u.email as email, u.mobile AS mobile, u.password as password ";
		} else if ("Relative".equals(profile)) {
			query = "MATCH (u:User)-[:RELATED]->(r:User)" + structureMatcher + " " +
					"WHERE has(r.password) and u.level contains {level} " + levelFilter +
					"RETURN r.id as id, u.ine as ine, head(r.profiles) as profile, r.lastName as lastName, r.firstName as firstName, " +
					"r.login as login, r.loginAlias as loginAlias, r.email as email, r.mobile AS mobile, r.password as password ";
		}
		else if ("Teacher".equals(profile) || "Personnel".equals(profile))
		{
			query = "MATCH (u:User)" + structureMatcher + " " +
							(levelContains != null ? "MATCH (u)-[:IN]->(:ProfileGroup {filter: {profile}})-[:DEPENDS]->(:Class {name:{level}}) " : "") +
							"WHERE HAS(u.password) AND u.firstName IS NOT NULL AND u.lastName IS NOT NULL AND u.birthDate IS NOT NULL AND {profile} IN u.profiles " +
							(levelNotContains != null ? "OPTIONAL MATCH (u)-[:IN]->(:ProfileGroup {filter: {profile}})-[:DEPENDS]->(c:Class {name:{notLevel}}) WITH u, c WHERE c = null " : "") +
							"RETURN u.id as id, head(u.profiles) as profile, u.lastName as lastName, u.firstName as firstName, u.birthDate as birthDate, " +
							"u.login as login, u.loginAlias as loginAlias, u.email as email, u.mobile AS mobile, u.password as password ";
			params.put("profile", profile);
		}
		else
		{
			handler.handle(new Either.Right<>(new JsonArray()));
			return;
		}
		if (stream) {
			query += "ORDER BY login ASC SKIP {skip} LIMIT {limit} ";
			params.put("limit", LIMIT);
			streamList(query, params, 0, LIMIT, handler);
		} else {
			neo.execute(query, params, validResultHandler(handler));
		}
	}

	private void streamList(String query, JsonObject params, int skip, int limit, Handler<Either<String, JsonArray>> handler) {
		neo.execute(query, params.copy().put("skip", skip), res -> {
			Either<String, JsonArray> r = Neo4jResult.validResult(res);
			handler.handle(r);
			if (r.isRight()) {
				if (r.right().getValue().size() == limit) {
					streamList(query, params, skip + limit, limit, handler);
				} else {
					handler.handle(new Either.Left<>(""));
				}
			}
		});
	}

	public void getMainStructure(String userId, JsonArray structuresToExclude, Handler<Either<String, JsonObject>> handler) {
	    String query = "MATCH (u:User {id : {userId}})-[:IN]->(Group)-[:DEPENDS]->(s:Structure) " +
                "WHERE NOT s.id IN {structuresIds} " +
                "OPTIONAL MATCH (u)-[:ADMINISTRATIVE_ATTACHMENT]->(s2:Structure) WHERE NOT s2.id IN {structuresIds} " +
                "RETURN CASE WHEN s2 IS NULL THEN s.name ELSE s2.name END AS name LIMIT 1";
	    JsonObject params = new JsonObject().put("userId", userId).put("structuresIds", structuresToExclude);
	    neo.execute(query, params, validUniqueResultHandler(res->{
	        if (res.isRight()) {
	            final JsonObject result = res.right().getValue();
	            handler.handle(new Either.Right<>(result));
	        } else {
	            handler.handle(res);
	        }
	    }));
	}


	public void getAttachmentSchool(String userId, JsonArray structuresToExclude, Handler<Either<String, JsonObject>> handler) {
		String query =
				"MATCH (u:User {id : {userId}})-[:ADMINISTRATIVE_ATTACHMENT]->(s:Structure) WHERE NOT s.id IN {structuresIds} " +
				"RETURN s.id AS id, s.name AS name";

		JsonObject params = new JsonObject()
				.put("userId", userId)
				.put("structuresIds", structuresToExclude);

		neo.execute(query, params, validUniqueResultHandler(res->{
			if (res.isRight()) {
				final JsonObject result = res.right().getValue();
				handler.handle(new Either.Right<>(result));
			} else {
				handler.handle(res);
			}
		}));
	}
}
