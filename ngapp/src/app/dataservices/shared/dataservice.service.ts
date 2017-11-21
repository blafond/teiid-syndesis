/**
 * @license
 * Copyright 2017 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Injectable } from "@angular/core";
import { Http } from "@angular/http";
import { ApiService } from "@core/api.service";
import { AppSettingsService } from "@core/app-settings.service";
import { LoggerService } from "@core/logger.service";
import { Dataservice } from "@dataservices/shared/dataservice.model";
import { DataservicesConstants } from "@dataservices/shared/dataservices-constants";
import { NewDataservice } from "@dataservices/shared/new-dataservice.model";
import { QueryResults } from "@dataservices/shared/query-results.model";
import { Table } from "@dataservices/shared/table.model";
import { VdbService } from "@dataservices/shared/vdb.service";
import { VdbsConstants } from "@dataservices/shared/vdbs-constants";
import { environment } from "@environments/environment";
import { Observable } from "rxjs/Observable";

@Injectable()
export class DataserviceService extends ApiService {

  public serviceVdbSuffix = "VDB";  // Don't change - must match komodo naming convention

  private http: Http;
  private vdbService: VdbService;
  private selectedDataservice: Dataservice;

  constructor( http: Http, vdbService: VdbService, appSettings: AppSettingsService, logger: LoggerService ) {
    super( appSettings, logger  );
    this.http = http;
    this.vdbService = vdbService;
  }

  /**
   * Set the current Dataservice selection
   * @param {Dataservice} service the Dataservice
   */
  public setSelectedDataservice(service: Dataservice): void {
    this.selectedDataservice = service;
  }

  /**
   * Get the current Dataservice selection
   * @returns {Dataservice} the selected Dataservice
   */
  public getSelectedDataservice( ): Dataservice {
    return this.selectedDataservice;
  }

  /**
   * Get the dataservices from the komodo rest interface
   * @returns {Observable<Dataservice[]>}
   */
  public getAllDataservices(): Observable<Dataservice[]> {
    return this.http
      .get(environment.komodoWorkspaceUrl + DataservicesConstants.dataservicesRootPath, this.getAuthRequestOptions())
      .map((response) => {
        const dataservices = response.json();
        return dataservices.map((dataservice) => Dataservice.create( dataservice ));
      })
      .catch( ( error ) => this.handleError( error ) );
  }

  /**
   * Create a dataservice via the komodo rest interface
   * @param {NewDataservice} dataservice
   * @returns {Observable<boolean>}
   */
  public createDataservice(dataservice: NewDataservice): Observable<boolean> {
    return this.http
      .post(environment.komodoWorkspaceUrl + DataservicesConstants.dataservicesRootPath + "/" + dataservice.getId(),
        dataservice, this.getAuthRequestOptions())
      .map((response) => {
        return response.ok;
      })
      .catch( ( error ) => this.handleError( error ) );
  }

  /**
   * Deploy a dataservice via the komodo rest interface
   * @param {string} dataserviceName
   * @returns {Observable<boolean>}
   */
  public deployDataservice(dataserviceName: string): Observable<boolean> {
    const servicePath = this.getKomodoUserWorkspacePath() + "/" + dataserviceName;
    return this.http
      .post(environment.komodoTeiidUrl + DataservicesConstants.dataserviceRootPath,
        { path: servicePath}, this.getAuthRequestOptions())
      .map((response) => {
        return response.ok;
      })
      .catch( ( error ) => this.handleError( error ) );
  }

  /**
   * Create a dataservice via the komodo rest interface
   * @param {string} dataserviceName,
   * @param {string} tablePath,
   * @param {string} modelSourcePath,
   * @returns {Observable<boolean>}
   */
  public setServiceVdbForSingleTable(dataserviceName: string, tablePath: string, modelSourcePath: string): Observable<boolean> {
    return this.http
      .post(environment.komodoWorkspaceUrl + DataservicesConstants.dataservicesRootPath + "/ServiceVdbForSingleTable",
        { dataserviceName, tablePath, modelSourcePath}, this.getAuthRequestOptions())
      .map((response) => {
        return response.ok;
      })
      .catch( ( error ) => this.handleError( error ) );
  }

  /**
   * Create a readonly datarole for the dataservice
   * @param {string} dataserviceName,
   * @param {string} model1Name,
   * @returns {Observable<boolean>}
   */
  public createReadonlyDataRole(dataserviceName: string, model1Name: string): Observable<boolean> {
    const serviceVdbName = dataserviceName + this.serviceVdbSuffix;
    const READ_ONLY_DATA_ROLE_NAME = VdbsConstants.DEFAULT_READONLY_DATA_ROLE;
    const VIEW_MODEL = VdbsConstants.SERVICE_VIEW_MODEL_NAME;
    const userWorkspacePath = this.getKomodoUserWorkspacePath();

    // The payload for the rest call
    const payload = {
      "keng__id": READ_ONLY_DATA_ROLE_NAME,
      "keng__kType": "VdbDataRole",
      "keng__dataPath": userWorkspacePath + "/" + serviceVdbName + "/vdb:dataRoles/" + READ_ONLY_DATA_ROLE_NAME,
      "vdb__dataRole": READ_ONLY_DATA_ROLE_NAME,
      "vdb__description": "The default read-only access data role.",
      "vdb__grantAll": false,
      "vdb__anyAuthenticated": true,
      "vdb__allowCreateTemporaryTables": false,
      "vdb__permissions": [
        {
          "keng__id": VIEW_MODEL,
          "keng__kType": "VdbPermission",
          "keng__dataPath": userWorkspacePath + "/" + serviceVdbName + "/vdb:dataRoles/" + READ_ONLY_DATA_ROLE_NAME
                                              + "/vdb:permissions/" + VIEW_MODEL,
          "vdb__permission": VIEW_MODEL,
          "vdb__allowAlter": false,
          "vdb__allowCreate": false,
          "vdb__allowDelete": false,
          "vdb__allowExecute": false,
          "vdb__allowRead": true,
          "vdb__allowUpdate": false
        },
        {
          "keng__id": model1Name,
          "keng__kType": "VdbPermission",
          "keng__dataPath": userWorkspacePath + "/" + serviceVdbName + "/vdb:dataRoles/" + READ_ONLY_DATA_ROLE_NAME
                                              + "/vdb:permissions/" + model1Name,
          "vdb__permission": model1Name,
          "vdb__allowAlter": false,
          "vdb__allowCreate": false,
          "vdb__allowDelete": false,
          "vdb__allowExecute": false,
          "vdb__allowRead": true,
          "vdb__allowUpdate": false
        }
      ]
    };
    const url = environment.komodoWorkspaceUrl + VdbsConstants.vdbsRootPath + "/" + serviceVdbName
                                               + "/VdbDataRoles/" + READ_ONLY_DATA_ROLE_NAME;
    const paystr = JSON.stringify(payload);

    return this.http
      .post(url, paystr, this.getAuthRequestOptions())
      .map((response) => {
        return response.ok;
      })
      .catch( ( error ) => this.handleError( error ) );
  }

  /**
   * Delete a dataservice via the komodo rest interface
   * @param {string} dataserviceId
   * @returns {Observable<boolean>}
   */
  public deleteDataservice(dataserviceId: string): Observable<boolean> {
    return this.http
      .delete(environment.komodoWorkspaceUrl + DataservicesConstants.dataservicesRootPath + "/" + dataserviceId,
               this.getAuthRequestOptions())
      .map((response) => {
        return response.ok;
      })
      .catch( ( error ) => this.handleError( error ) );
  }

  /**
   * Create a dataservice which is a straight passthru to the supplied tables
   * @param {NewDataservice} dataservice
   * @param {Table} sourceTable
   * @returns {Observable<boolean>}
   */
  public createDataserviceForSingleTable(dataservice: NewDataservice, sourceTable: Table): Observable<boolean> {
    const connectionName = sourceTable.getConnection().getId();
    const sourceVdbName = connectionName + VdbsConstants.SOURCE_VDB_SUFFIX;
    const sourceModelName = connectionName;
    const vdbPath = this.getKomodoUserWorkspacePath() + "/" + sourceVdbName;
    const tablePath = vdbPath + "/" + sourceModelName + "/" + sourceTable.getName();
    const modelSourcePath = vdbPath + "/" + sourceModelName + "/vdb:sources/" + sourceModelName;

    // Chain the individual calls together in series to build the DataService
    return this.createDataservice(dataservice)
      .flatMap((res) => this.vdbService.updateVdbModelFromTeiid(sourceVdbName, sourceModelName,
                                                                sourceVdbName, sourceModelName))
      .flatMap((res) => this.setServiceVdbForSingleTable(dataservice.getId(), tablePath, modelSourcePath))
      .flatMap((res) => this.createReadonlyDataRole(dataservice.getId(), sourceModelName))
      .flatMap((res) => this.vdbService.undeployVdb(sourceVdbName))
      .flatMap((res) => this.vdbService.deleteVdb(sourceVdbName));
  }

  /**
   * Export a dataservice to a git repository
   * @param {string} dataserviceName the dataservice name
   * @returns {Observable<boolean>}
   */
  public exportDataservice(dataserviceName: string): Observable<boolean> {
    const repoPathKey = this.appSettings.GIT_REPO_PATH_KEY;
    const repoBranchKey = this.appSettings.GIT_REPO_BRANCH_KEY;
    const repoUsernameKey = this.appSettings.GIT_REPO_USERNAME_KEY;
    const repoPasswordKey = this.appSettings.GIT_REPO_PASSWORD_KEY;
    const repoAuthorEmailKey = this.appSettings.GIT_REPO_AUTHOR_EMAIL_KEY;
    const repoAuthorNameKey = this.appSettings.GIT_REPO_AUTHOR_NAME_KEY;
    const repoFilePathKey = this.appSettings.GIT_REPO_FILE_PATH_KEY;

    // The payload for the rest call
    const payload = {
      "storageType": "git",
      "dataPath": "/" + this.getKomodoUserWorkspacePath() + "/" + dataserviceName,
      "parameters":
        {
          [repoPathKey] : this.appSettings.getGitRepoProperty(repoPathKey),
          [repoBranchKey] : this.appSettings.getGitRepoProperty(repoBranchKey),
          [repoFilePathKey] : dataserviceName,
          [repoUsernameKey] : this.appSettings.getGitRepoProperty(repoUsernameKey),
          [repoPasswordKey] : btoa(this.appSettings.getGitRepoProperty(repoPasswordKey)),
          [repoAuthorNameKey] : this.appSettings.getGitRepoProperty(repoAuthorNameKey),
          [repoAuthorEmailKey] : this.appSettings.getGitRepoProperty(repoAuthorEmailKey)
        }
    };

    const url = environment.komodoImportExportUrl + "/" + DataservicesConstants.dataservicesExport;

    return this.http
      .post(url, payload, this.getAuthRequestOptions())
      .map((response) => {
        return response.ok;
      })
      .catch( ( error ) => this.handleError( error ) );
  }

  /**
   * Query a Dataservice via the komodo rest interface
   * @param {string} query the SQL query
   * @param {string} dataserviceName the dataservice name
   * @param {number} limit the limit for the number of result rows
   * @param {number} offset the offset for the result rows
   * @returns {Observable<boolean>}
   */
  public queryDataservice(query: string, dataserviceName: string, limit: number, offset: number): Observable<any> {
    // The payload for the rest call
    const payload = {
      "query": query,
      "target": dataserviceName,
      "limit": limit,
      "offset": offset
    };

    const url = environment.komodoTeiidUrl + "/query";

    return this.http
      .post(url, payload, this.getAuthRequestOptions())
      .map((response) => {
        const queryResults = response.json();
        return new QueryResults(queryResults);
      })
      .catch( ( error ) => this.handleError( error ) );
  }

}
