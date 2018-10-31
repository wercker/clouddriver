/*
 * Copyright (c) 2017, 2018, Oracle Corporation and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the Apache License Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * If a copy of the Apache License Version 2.0 was not distributed with this file,
 * You can obtain one at https://www.apache.org/licenses/LICENSE-2.0.html
 */
package com.netflix.spinnaker.clouddriver.oracle.deploy.op

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.clouddriver.data.task.Task
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository
import com.netflix.spinnaker.clouddriver.oracle.deploy.OracleWorkRequestPoller
import com.netflix.spinnaker.clouddriver.oracle.deploy.converter.CreateOracleLoadBalancerAtomicOperationConverter
import com.netflix.spinnaker.clouddriver.oracle.deploy.description.CreateLoadBalancerDescription
import com.netflix.spinnaker.clouddriver.oracle.security.OracleNamedAccountCredentials
import com.netflix.spinnaker.clouddriver.security.AccountCredentialsProvider
import com.oracle.bmc.loadbalancer.LoadBalancerClient
import com.oracle.bmc.loadbalancer.model.CreateLoadBalancerDetails
import com.oracle.bmc.loadbalancer.model.BackendDetails
import com.oracle.bmc.loadbalancer.model.BackendSet
import com.oracle.bmc.loadbalancer.model.BackendSetDetails
import com.oracle.bmc.loadbalancer.model.CreateBackendSetDetails
import com.oracle.bmc.loadbalancer.model.HealthCheckerDetails
import com.oracle.bmc.loadbalancer.model.ListenerDetails
import com.oracle.bmc.loadbalancer.model.LoadBalancer
import com.oracle.bmc.loadbalancer.model.UpdateBackendSetDetails
import com.oracle.bmc.loadbalancer.model.UpdateListenerDetails
import com.oracle.bmc.loadbalancer.requests.CreateBackendSetRequest
import com.oracle.bmc.loadbalancer.requests.CreateLoadBalancerRequest
import com.oracle.bmc.loadbalancer.requests.DeleteBackendSetRequest
import com.oracle.bmc.loadbalancer.requests.UpdateBackendSetRequest
import com.oracle.bmc.loadbalancer.responses.CreateBackendSetResponse
import com.oracle.bmc.loadbalancer.responses.CreateLoadBalancerResponse
import com.oracle.bmc.loadbalancer.responses.DeleteBackendSetResponse
import com.oracle.bmc.loadbalancer.responses.GetLoadBalancerResponse
import com.oracle.bmc.loadbalancer.responses.UpdateBackendSetResponse
import spock.lang.Shared
import spock.lang.Specification

class CreateOracleLoadBalancerAtomicOperationSpec extends Specification {
  
  @Shared
  ObjectMapper mapper = new ObjectMapper()

  @Shared
  CreateOracleLoadBalancerAtomicOperationConverter converter

  def setupSpec() {
    this.converter = new CreateOracleLoadBalancerAtomicOperationConverter(objectMapper: mapper)
    converter.accountCredentialsProvider = Mock(AccountCredentialsProvider)
    converter.accountCredentialsProvider.getCredentials(_) >> Mock(OracleNamedAccountCredentials)
  }

  def "Create LoadBalancer"() {
    setup:
    def req = read('createLoadBalancer1.json')
    def desc = converter.convertDescription(req[0].upsertLoadBalancer)

    def creds = Mock(OracleNamedAccountCredentials)
    def loadBalancerClient = Mock(LoadBalancerClient)
    creds.loadBalancerClient >> loadBalancerClient
    desc.credentials = creds

    GroovySpy(OracleWorkRequestPoller, global: true)

    TaskRepository.threadLocalTask.set(Mock(Task))
    def op = new CreateOracleLoadBalancerAtomicOperation(desc)

    when:
    op.operate(null)

    then:

    1 * loadBalancerClient.createLoadBalancer(_) >> { args ->
      CreateLoadBalancerDetails lb = args[0].getCreateLoadBalancerDetails()
      def listener = lb.listeners.get('HTTP_80')
      assert lb.getIsPrivate()
      assert lb.getShapeName() == '400Mbps'
      assert lb.listeners.size() == 1
      assert listener.port == 80
      assert listener.protocol == 'HTTP'
      assert listener.defaultBackendSetName == 'backendSet1'
      assert lb.backendSets.size() == 1
      assert lb.backendSets.backendSet1.policy == 'ROUND_ROBIN'
      assert lb.backendSets.backendSet1.healthChecker.port == 80
      assert lb.backendSets.backendSet1.healthChecker.protocol == 'HTTP'
      assert lb.backendSets.backendSet1.healthChecker.urlPath == '/healthZ'
      CreateLoadBalancerResponse.builder().opcWorkRequestId("wr1").build()
    }
    1 * OracleWorkRequestPoller.poll("wr1", _, _, loadBalancerClient) >> null
  }

  def "Create LoadBalancer with 2 Listeners"() {
    setup:
    def req = read('createLoadBalancer2.json')
    def desc = converter.convertDescription(req[0].upsertLoadBalancer)

    def creds = Mock(OracleNamedAccountCredentials)
    def loadBalancerClient = Mock(LoadBalancerClient)
    creds.loadBalancerClient >> loadBalancerClient
    desc.credentials = creds

    GroovySpy(OracleWorkRequestPoller, global: true)

    TaskRepository.threadLocalTask.set(Mock(Task))
    def op = new CreateOracleLoadBalancerAtomicOperation(desc)

    when:
    op.operate(null)

    then:

    1 * loadBalancerClient.createLoadBalancer(_) >> { args ->
      CreateLoadBalancerDetails lb = args[0].getCreateLoadBalancerDetails()
      assert lb.getIsPrivate()
      assert lb.listeners.size() == 2
      assert lb.listeners.httpListener.port == 8080
      assert lb.listeners.httpListener.protocol == 'HTTP'
      assert lb.listeners.httpsListener.port == 8081
      assert lb.listeners.httpsListener.protocol == 'HTTPS'
      assert lb.backendSets.size() == 1
      assert lb.backendSets.myBackendSet.policy == 'ROUND_ROBIN'
      assert lb.backendSets.myBackendSet.healthChecker.port == 80
      CreateLoadBalancerResponse.builder().opcWorkRequestId("wr1").build()
    }
    1 * OracleWorkRequestPoller.poll("wr1", _, _, loadBalancerClient) >> null
  }

  def "Update LoadBalancer with BackendSets"() {
    setup:
    def loadBalancerId = 'updateLoadBalancerBackendSets';
    def req = read('updateLoadBalancerBackendSets.json')
    def desc = converter.convertDescription(req[0].upsertLoadBalancer)

    def creds = Mock(OracleNamedAccountCredentials)
    def loadBalancerClient = Mock(LoadBalancerClient)
    creds.loadBalancerClient >> loadBalancerClient
    desc.credentials = creds

    GroovySpy(OracleWorkRequestPoller, global: true)

    TaskRepository.threadLocalTask.set(Mock(Task))
    def op = new CreateOracleLoadBalancerAtomicOperation(desc)
    def backendSets = [ 
      // to be removed
      'myBackendSet0': BackendSet.builder().name('myBackendSet0').backends([]).build(), 
      // to be updated
      'myBackendSet1': BackendSet.builder().name('myBackendSet1').backends([]).build(), 
    ]

    when:
    op.operate(null)

    then:
    1 * loadBalancerClient.getLoadBalancer(_) >> 
      GetLoadBalancerResponse.builder().loadBalancer(LoadBalancer.builder().id(loadBalancerId).backendSets(backendSets).build()).build()
    1 * loadBalancerClient.deleteBackendSet(_) >> { args ->
      DeleteBackendSetRequest delBksReq = args[0]
      assert delBksReq.getLoadBalancerId() == loadBalancerId
      assert delBksReq.getBackendSetName() == 'myBackendSet0'
      DeleteBackendSetResponse.builder().opcWorkRequestId("wr0").build()
    }
    1 * OracleWorkRequestPoller.poll("wr0", _, _, loadBalancerClient) >> null
    1 * loadBalancerClient.updateBackendSet(_) >> { args ->
      UpdateBackendSetRequest upBksReq = args[0]
      assert upBksReq.getLoadBalancerId() == loadBalancerId
      assert upBksReq.getBackendSetName() == 'myBackendSet1'
      UpdateBackendSetResponse.builder().opcWorkRequestId("wr1").build()
    }
    1 * OracleWorkRequestPoller.poll("wr1", _, _, loadBalancerClient) >> null
    1 * loadBalancerClient.createBackendSet(_) >> { args ->
      CreateBackendSetRequest crBksReq = args[0]
      assert crBksReq.getLoadBalancerId() == loadBalancerId
      assert crBksReq.getCreateBackendSetDetails().getName() == 'myBackendSet2'
      CreateBackendSetResponse.builder().opcWorkRequestId("wr2").build()
    }
    1 * OracleWorkRequestPoller.poll("wr2", _, _, loadBalancerClient) >> null
  }
  

  def read(String fileName) {
    def json = new File(getClass().getResource('/desc/' + fileName).toURI()).text
    List<Map<String, Object>> data = mapper.readValue(json, new TypeReference<List<Map<String, Object>>>(){});
    return data;
  }

}
