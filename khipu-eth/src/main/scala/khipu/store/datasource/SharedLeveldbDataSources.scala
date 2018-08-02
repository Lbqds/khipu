package khipu.store.datasource

import khipu.util.Config

trait SharedLeveldbDataSources extends DataSources {
  val dataSource = LeveldbDataSource(Config.Db.Leveldb)

  //val blockHeadersDataSource = dataSource
  //val blockBodiesDataSource = dataSource
  //val receiptsDataSource = dataSource
  val totalDifficultyDataSource = dataSource
  val transactionMappingDataSource = dataSource

  val mptDataSource = dataSource
  val fastSyncStateDataSource = dataSource
  val appStateDataSource = dataSource

  val blockHeightsHashesDataSource = dataSource
  val knownNodesDataSource = dataSource

  def closeAll() {
    dataSource.close()
  }
}
