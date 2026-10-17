const express=require('express');
const router=express.Router();
const {DB}=require('../db');
const {authenticate,requireRole}=require('../middleware/auth');
router.get('/my-driver',authenticate,requireRole('PASSENGER'),async(req,res)=>{
  try{
    const driver=await DB.prepare(`SELECT u.id AS driver_id,u.fullName AS driver_name,u.phone AS driver_phone,
      v.plateNumber AS vehicle_plate,v.vehicleType AS vehicle_type,v.model AS vehicle_model,
      p.vehicle_color,r.name AS route_name
      FROM subscriptions s JOIN routes r ON r.id=s.routeId AND COALESCE(r.companyId,'')='' AND COALESCE(r.routeType,'DAILY_COMMUTE')='DAILY_COMMUTE'
      JOIN vehicles v ON v.id=s.vehicleId AND v.assignedRouteId=s.routeId AND COALESCE(v.companyId,'')=''
      JOIN users u ON u.id=v.driverId AND u.status='ACTIVE' AND COALESCE(u.companyId,'')=''
      LEFT JOIN user_profiles p ON p.user_id=u.id WHERE s.passengerId=? ORDER BY s.updatedAt DESC LIMIT 1`).get(req.user.id);
    res.json({success:true,driver:driver?{userId:driver.driver_id,fullName:driver.driver_name,phone:driver.driver_phone,photoUrl:`/api/profiles/${encodeURIComponent(driver.driver_id)}/photo`,vehicleType:driver.vehicle_type,vehicleColor:driver.vehicle_color||'Not provided',plateNumber:driver.vehicle_plate,model:driver.vehicle_model,routeName:driver.route_name}:null});
  }catch(e){console.error('[Assigned driver]',e);res.status(500).json({success:false,error:'Could not load assigned driver.'});}
});
module.exports=router;
