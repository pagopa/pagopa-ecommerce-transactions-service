module "github_runner_app" {
  source = "git::https://github.com/pagopa/github-actions-tf-modules.git//app-github-runner-creator?ref=main"

  app_name = local.app_name

  subscription_id = data.azurerm_subscription.current.id

  github_org              = local.github.org
  github_repository       = local.github.repository
  github_environment_name = var.env

  container_app_github_runner_env_rg = local.container_app_environment.resource_group
}

resource "null_resource" "github_runner_app_permissions_to_namespace" {
  triggers = {
    aks_id               = data.azurerm_kubernetes_cluster.aks.id
    service_principal_id = module.github_runner_app.client_id
    namespace            = local.domain
    version              = "v2"
  }

  provisioner "local-exec" {
    command = <<EOT
      az role assignment create --role "Azure Kubernetes Service RBAC Admin" \
      --assignee ${self.triggers.service_principal_id} \
      --scope ${self.triggers.aks_id}/namespaces/${self.triggers.namespace}

      az role assignment list --role "Azure Kubernetes Service RBAC Admin"  \
      --scope ${self.triggers.aks_id}/namespaces/${self.triggers.namespace}
    EOT
  }

  provisioner "local-exec" {
    when    = destroy
    command = <<EOT
      az role assignment delete --role "Azure Kubernetes Service RBAC Admin" \
      --assignee ${self.triggers.service_principal_id} \
      --scope ${self.triggers.aks_id}/namespaces/${self.triggers.namespace}
    EOT
  }
}

resource "azurerm_role_assignment" "environment_terraform_resource_group_dashboards" {
  scope                = data.azurerm_resource_group.dashboards.id
  role_definition_name = "Contributor"
  principal_id         = module.github_runner_app.object_id
}




resource "azuread_application" "action" {
  display_name = "github-${local.github.org}-${local.github.repository}-${var.env}"
}

resource "azuread_service_principal" "action" {
  application_id = azuread_application.action.application_id
}

resource "azurerm_role_assignment" "environment_terraform_subscription" {
  scope                = data.azurerm_subscription.current.id
  role_definition_name = "Reader"
  principal_id         = azuread_service_principal.action.object_id
}

resource "azurerm_role_assignment" "environment_key_vault" {
  scope                = data.azurerm_key_vault.key_vault.id
  role_definition_name = "Reader"
  principal_id         = azuread_service_principal.action.object_id
}

resource "azurerm_role_assignment" "environment_receipts_sa_role" {
  scope                = data.azurerm_storage_account.receipts_sa.id
  role_definition_name = "Contributor"
  principal_id         = azuread_service_principal.action.object_id
}

resource "azurerm_key_vault_access_policy" "ad_group_policy" {
  key_vault_id = data.azurerm_key_vault.key_vault.id

  tenant_id = data.azurerm_client_config.current.tenant_id
  object_id = azuread_service_principal.action.object_id

  key_permissions         = ["Get", "List", "Import" ]
  secret_permissions      = ["Get", "List"]
  storage_permissions     = []
  certificate_permissions = []
}
