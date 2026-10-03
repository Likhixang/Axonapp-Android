package cc.khixang.axonhub.gateway

/** Exact documents from Axonapp-iOS ChannelModelOperations/Diagnostics/Templates. */
internal object GatewayToolDocuments {
    const val ChannelDuplicate = """mutation ChannelDuplicate(${ '$' }sourceID: ID!, ${ '$' }input: CreateChannelInput!) { duplicateChannel(sourceID: ${ '$' }sourceID, input: ${ '$' }input) { id } }"""
    const val ChannelTestKeys = """mutation ChannelTestKeys(${ '$' }id: ID!, ${ '$' }model: String) { testChannelAPIKeys(channelID: ${ '$' }id, modelID: ${ '$' }model) { channelID total successCount failedCount results { success latency disabled } } }"""
    const val ModelRouteConnections = """query ModelRouteConnections(${ '$' }associations: [ModelAssociationInput!]!) { queryModelChannelConnections(associations: ${ '$' }associations) { channel { id name status } priority models { requestModel actualModel source } } }"""
    const val ModelUnassociatedChannels = """query ModelUnassociatedChannels { queryUnassociatedChannels { channel { id name status } models } }"""
    const val ModelProvidersCatalogFiltered = """query ModelProvidersCatalogFiltered { providersCatalog(filtered: true) { data source fetchedAt filtered } }"""
    const val ModelProvidersCatalog = """query ModelProvidersCatalog { providersCatalog(filtered: false) { data source fetchedAt filtered } }"""
    const val ModelRefreshProvidersCatalog = """mutation ModelRefreshProvidersCatalog { refreshProvidersCatalog { data source fetchedAt filtered } }"""
    const val ModelBulkCreate = """mutation ModelBulkCreate(${ '$' }inputs: [CreateModelInput!]!) { bulkCreateModels(inputs: ${ '$' }inputs) { id } }"""
    const val ChannelBulkArchive = """mutation ChannelBulkArchive(${ '$' }ids: [ID!]!) { bulkArchiveChannels(ids: ${ '$' }ids) }"""
    const val ChannelBulkDelete = """mutation ChannelBulkDelete(${ '$' }ids: [ID!]!) { bulkDeleteChannels(ids: ${ '$' }ids) }"""
    const val ChannelBulkRecover = """mutation ChannelBulkRecover(${ '$' }ids: [ID!]!) { bulkRecoverChannels(ids: ${ '$' }ids) }"""
    const val ModelBulkArchive = """mutation ModelBulkArchive(${ '$' }ids: [ID!]!) { bulkArchiveModels(ids: ${ '$' }ids) }"""
    const val ModelBulkDelete = """mutation ModelBulkDelete(${ '$' }ids: [ID!]!) { bulkDeleteModels(ids: ${ '$' }ids) }"""
    const val ChannelDisableKey = """mutation ChannelDisableKey(${ '$' }id: ID!, ${ '$' }key: String!) { disableChannelAPIKey(channelID: ${ '$' }id, key: ${ '$' }key) }"""
    const val ChannelEnableKey = """mutation ChannelEnableKey(${ '$' }id: ID!, ${ '$' }key: String!) { enableChannelAPIKey(channelID: ${ '$' }id, key: ${ '$' }key) }"""
    const val ChannelEnableAllKeys = """mutation ChannelEnableAllKeys(${ '$' }id: ID!) { enableAllChannelAPIKeys(channelID: ${ '$' }id) }"""
    const val ChannelEnableSelectedKeys = """mutation ChannelEnableSelectedKeys(${ '$' }id: ID!, ${ '$' }keys: [String!]!) { enableSelectedChannelAPIKeys(channelID: ${ '$' }id, keys: ${ '$' }keys) }"""
    const val ChannelDeleteDisabledKeys = """mutation ChannelDeleteDisabledKeys(${ '$' }id: ID!, ${ '$' }keys: [String!]!) { deleteDisabledChannelAPIKeys(channelID: ${ '$' }id, keys: ${ '$' }keys) { success } }"""
    const val ChannelTestKey = """mutation ChannelTestKey(${ '$' }id: ID!, ${ '$' }key: String!, ${ '$' }model: String) { testChannelAPIKey(channelID: ${ '$' }id, key: ${ '$' }key, modelID: ${ '$' }model) { success latency disabled } }"""
    const val ChannelBulkCreate = """mutation ChannelBulkCreate(${ '$' }input: BulkCreateChannelsInput!) { bulkCreateChannels(input: ${ '$' }input) { id } }"""
    const val ChannelBulkImport = """mutation ChannelBulkImport(${ '$' }input: BulkImportChannelsInput!) { bulkImportChannels(input: ${ '$' }input) { success created failed channels { id } } }"""
    const val ChannelBulkOrdering = """mutation ChannelBulkOrdering(${ '$' }input: BulkUpdateChannelOrderingInput!) { bulkUpdateChannelOrdering(input: ${ '$' }input) { success updated channels { id orderingWeight } } }"""
    const val ChannelSavePrices = """mutation ChannelSavePrices(${ '$' }id: ID!, ${ '$' }input: [SaveChannelModelPriceInput!]!) { saveChannelModelPrices(channelId: ${ '$' }id, input: ${ '$' }input) { id modelID } }"""
    const val ChannelPrices = """query ChannelPrices(${ '$' }id: ID!) {
channels(first: 1, where: {id: ${ '$' }id}) { edges { node { id channelModelPrices { modelID price {
items { itemCode pricing { mode flatFee usagePerUnit usageTiered { tiers { upTo pricePerUnit } } }
promptWriteCacheVariants { variantCode pricing { mode flatFee usagePerUnit usageTiered { tiers { upTo pricePerUnit } } } } }
schedule { timezone overrides { name priority when { dailyTime { start end } weekdays dateRange { start end } }
items { itemCode pricing { mode flatFee usagePerUnit usageTiered { tiers { upTo pricePerUnit } } }
promptWriteCacheVariants { variantCode pricing { mode flatFee usagePerUnit usageTiered { tiers { upTo pricePerUnit } } } } } } }
} } } } }
}"""
    const val ChannelClearError = """mutation ChannelClearError(${ '$' }id: ID!) { updateChannel(id: ${ '$' }id, input: {clearErrorMessage: true}) { id errorMessage } }"""
    const val ChannelDiagnostics = """query ChannelDiagnostics(${ '$' }id: ID!) { channels(first: 1, where: {id: ${ '$' }id}) { edges { node { id liveLimiterStats { inFlight waiting capacity queueSize } providerQuotaStatus { id updatedAt providerType status nextResetAt ready nextCheckAt quotaData } allModelEntries { requestModel actualModel source } } } } }"""
    const val ChannelQuotaReset = """mutation ChannelQuotaReset(${ '$' }id: ID!) { resetChannelQuotaNow(channelID: ${ '$' }id) }"""
    const val ChannelTestHistory = """query ChannelTestHistory(${ '$' }id: ID!, ${ '$' }after: Cursor) { requests(first: 50, after: ${ '$' }after, where: {channelID: ${ '$' }id, sourceIn: [test]}, orderBy: {field: CREATED_AT, direction: DESC}) { edges { node { id createdAt modelID status metricsLatencyMs } } pageInfo { hasNextPage endCursor } totalCount } }"""
    const val ChannelTemplates = """query ChannelTemplates(${ '$' }after: Cursor) { channelOverrideTemplates(first: 100, after: ${ '$' }after) { edges { node { id name description headerOverrideOperations { op path from to value condition match { path eq } index splat } bodyOverrideOperations { op path from to value condition match { path eq } index splat } } } pageInfo { hasNextPage endCursor } } }"""
    const val ChannelTemplateCreate = """mutation ChannelTemplateCreate(${ '$' }input: CreateChannelOverrideTemplateInput!) { createChannelOverrideTemplate(input: ${ '$' }input) { id } }"""
    const val ChannelTemplateEdit = """mutation ChannelTemplateEdit(${ '$' }id: ID!, ${ '$' }input: UpdateChannelOverrideTemplateInput!) { updateChannelOverrideTemplate(id: ${ '$' }id, input: ${ '$' }input) { id } }"""
    const val ChannelTemplateDelete = """mutation ChannelTemplateDelete(${ '$' }id: ID!) { deleteChannelOverrideTemplate(id: ${ '$' }id) }"""
    const val ChannelTemplateApply = """mutation ChannelTemplateApply(${ '$' }input: ApplyChannelOverrideTemplateInput!) { applyChannelOverrideTemplate(input: ${ '$' }input) { success updated channels { id } } }"""
    const val ChannelTemplateClear = """mutation ChannelTemplateClear(${ '$' }input: ClearChannelOverrideTemplatesInput!) { clearChannelOverrideTemplates(input: ${ '$' }input) { success updated channels { id } } }"""
}
