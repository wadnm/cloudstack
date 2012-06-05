// Copyright 2012 Citrix Systems, Inc. Licensed under the
// Apache License, Version 2.0 (the "License"); you may not use this
// file except in compliance with the License.  Citrix Systems, Inc.
// reserves all rights not expressly granted by the License.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// 
// Automatically generated by addcopyright.py at 04/03/2012
package com.cloud.network.router;

import java.util.List;
import java.util.Map;

import javax.ejb.Local;

import org.apache.log4j.Logger;

import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlan;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.NetworkService;
import com.cloud.network.PhysicalNetwork;
import com.cloud.network.PhysicalNetworkServiceProvider;
import com.cloud.network.VirtualRouterProvider;
import com.cloud.network.VirtualRouterProvider.VirtualRouterProviderType;
import com.cloud.network.addr.PublicIp;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.vpc.Vpc;
import com.cloud.network.vpc.Dao.VpcDao;
import com.cloud.network.vpc.Dao.VpcOfferingDao;
import com.cloud.user.Account;
import com.cloud.utils.Pair;
import com.cloud.utils.component.Inject;
import com.cloud.utils.db.DB;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.VirtualMachineProfile.Param;

/**
 * @author Alena Prokharchyk
 */

@Local(value = {VpcVirtualNetworkApplianceManager.class})
public class VpcVirtualNetworkApplianceManagerImpl extends VirtualNetworkApplianceManagerImpl implements VpcVirtualNetworkApplianceManager{
    private static final Logger s_logger = Logger.getLogger(VpcVirtualNetworkApplianceManagerImpl.class);

    @Inject
    VpcDao _vpcDao = null;
    @Inject
    VpcOfferingDao _vpcOffDao = null;
    @Inject
    PhysicalNetworkDao _pNtwkDao = null;
    @Inject
    NetworkService _ntwkService = null;
    
    @Override
    public List<DomainRouterVO> deployVirtualRouterInVpc(Vpc vpc, DeployDestination dest, Account owner, 
            Map<Param, Object> params) throws InsufficientCapacityException,
            ConcurrentOperationException, ResourceUnavailableException {

        List<DomainRouterVO> routers = findOrDeployVirtualRouterInVpc(vpc, dest, owner, params);
        
        return startRouters(params, routers);
    }
    
    @DB
    protected List<DomainRouterVO> findOrDeployVirtualRouterInVpc(Vpc vpc, DeployDestination dest, Account owner,
            Map<Param, Object> params) throws ConcurrentOperationException, 
            InsufficientCapacityException, ResourceUnavailableException {

        s_logger.debug("Deploying Virtual Router in VPC "+ vpc);
        Vpc vpcLock = _vpcDao.acquireInLockTable(vpc.getId());
        if (vpcLock == null) {
            throw new ConcurrentOperationException("Unable to lock vpc " + vpc.getId());
        }
        
        //1) Get deployment plan and find out the list of routers     
        Pair<DeploymentPlan, List<DomainRouterVO>> planAndRouters = getDeploymentPlanAndRouters(vpc.getId(), dest);
        DeploymentPlan plan = planAndRouters.first();
        List<DomainRouterVO> routers = planAndRouters.second();
        
        //2) Return routers if exist
        if (routers.size() >= 1) {
            return routers;
        }
        
        Long offeringId = _vpcOffDao.findById(vpc.getVpcOfferingId()).getServiceOfferingId();
        if (offeringId == null) {
            offeringId = _offering.getId();
        }
        
        //3) Deploy Virtual Router
        try {
            List<? extends PhysicalNetwork> pNtwks = _pNtwkDao.listByZone(vpc.getZoneId());
            
            VirtualRouterProvider vpcVrProvider = null;
           
            for (PhysicalNetwork pNtwk : pNtwks) {
                PhysicalNetworkServiceProvider provider = _physicalProviderDao.findByServiceProvider(pNtwk.getId(), 
                        VirtualRouterProviderType.VPCVirtualRouter.toString());
                if (provider == null) {
                    throw new CloudRuntimeException("Cannot find service provider " + 
                            VirtualRouterProviderType.VPCVirtualRouter.toString() + " in physical network " + pNtwk.getId());
                }
                vpcVrProvider = _vrProviderDao.findByNspIdAndType(provider.getId(), 
                        VirtualRouterProviderType.VPCVirtualRouter);
                if (vpcVrProvider != null) {
                    break;
                }
            }
            
            PublicIp sourceNatIp = _networkMgr.assignSourceNatIpAddressToVpc(owner, vpc);
            
            DomainRouterVO router = deployRouter(owner, dest, plan, params, false, vpcVrProvider, offeringId,
                    vpc.getId(), sourceNatIp);
            routers.add(router);
            
        } finally {
            if (vpcLock != null) {
                _vpcDao.releaseFromLockTable(vpc.getId());
            }
        }
        return routers;
    }
    
    protected Pair<DeploymentPlan, List<DomainRouterVO>> getDeploymentPlanAndRouters(long vpcId, DeployDestination dest) {
        long dcId = dest.getDataCenter().getId();
        
        DeploymentPlan plan = new DataCenterDeployment(dcId);
        List<DomainRouterVO> routers = _routerDao.listRoutersByVpcId(vpcId);
        
        return new Pair<DeploymentPlan, List<DomainRouterVO>>(plan, routers);
    }
    
}
