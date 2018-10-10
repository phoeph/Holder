package com.orco.holder.deploy.master

private[deploy] object RecoveryState extends Enumeration {
  type MasterState = Value

  val STANDBY, ALIVE, RECOVERING, COMPLETING_RECOVERY = Value
}
