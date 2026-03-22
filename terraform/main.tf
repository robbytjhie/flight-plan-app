# ─────────────────────────────────────────────────────────────────────────────
# Terraform — flight-plan-backend AWS infrastructure
# Region: ap-southeast-1 (Singapore)
#
# Resources provisioned:
#   VPC (2 AZ, public + private subnets)
#   ECR repository
#   ECS Fargate cluster + service + task definition
#   Application Load Balancer (HTTPS)
#   ACM certificate (HTTPS termination at ALB)
#   ElastiCache Redis (leader election lock)
#   IAM roles (ECS task execution + task role)
#   CloudWatch log groups
#   Secrets Manager (env secrets injected into ECS tasks)
# ─────────────────────────────────────────────────────────────────────────────

terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  # Remote state — replace bucket/key with your values
  backend "s3" {
    bucket         = "flight-plan-tfstate"
    key            = "flight-plan-backend/terraform.tfstate"
    region         = "ap-southeast-1"
    encrypt        = true
    dynamodb_table = "flight-plan-tfstate-lock"
  }
}

provider "aws" {
  region = var.aws_region
  default_tags {
    tags = {
      Project     = "flight-plan-backend"
      Environment = var.environment
      ManagedBy   = "Terraform"
    }
  }
}

# ── Variables ─────────────────────────────────────────────────────────────────

variable "aws_region"        { default = "ap-southeast-1" }
variable "environment"       { default = "prod" }                                    # was: description = "staging | prod" — fixed to prod; flightplan-staging commented out below
variable "app_image_tag"     { description = "ECR image tag to deploy" }
variable "domain_name"       { description = "e.g. api.flightplan.example.io" }
variable "acm_certificate_arn" { description = "ACM cert ARN for HTTPS on ALB" }
variable "cors_allowed_origins" { description = "Comma-separated frontend origins" }
variable "desired_count"     { default = 1 }                                         # was: 2 — reduced to 1 pod
variable "task_cpu"          { default = 512  }
variable "task_memory"       { default = 1024 }

# ── Data sources ──────────────────────────────────────────────────────────────

data "aws_caller_identity" "current" {}

data "aws_ecr_repository" "app" {
  name = "flight-plan-backend"
}

# ── VPC ───────────────────────────────────────────────────────────────────────

module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 5.0"

  name = "flight-plan-${var.environment}"
  cidr = "10.0.0.0/16"

  azs              = ["${var.aws_region}a", "${var.aws_region}b"]
  private_subnets  = ["10.0.1.0/24", "10.0.2.0/24"]
  public_subnets   = ["10.0.101.0/24", "10.0.102.0/24"]

  enable_nat_gateway   = true
  single_nat_gateway   = true                                                        # was: var.environment == "staging" — always single NAT; flightplan-staging removed
  enable_dns_hostnames = true
}

# ── ECR ───────────────────────────────────────────────────────────────────────

resource "aws_ecr_repository" "app" {
  name                 = "flight-plan-backend"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true       # IM8 S6: vulnerability scanning on every push
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

resource "aws_ecr_lifecycle_policy" "app" {
  repository = aws_ecr_repository.app.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep last 10 images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 10
      }
      action = { type = "expire" }
    }]
  })
}

# ── EKS Cluster ───────────────────────────────────────────────────────────────
# Single cluster (flightplan-prod) replaces flightplan-prod + flightplan-staging.
# flightplan-staging is commented out — all branches now deploy to flightplan-prod.

module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 20.0"

  cluster_name    = "flightplan-prod"
  cluster_version = "1.29"

  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_subnets

  cluster_endpoint_public_access = true

  eks_managed_node_groups = {
    default = {
      instance_types = ["t3.small"]
      min_size       = 1
      max_size       = 3
      desired_size   = 1
    }
  }

  enable_cluster_creator_admin_permissions = true
}

# flightplan-staging cluster — commented out; use flightplan-prod for all deployments
# module "eks_staging" {
#   source  = "terraform-aws-modules/eks/aws"
#   version = "~> 20.0"
#
#   cluster_name    = "flightplan-staging"
#   cluster_version = "1.29"
#
#   vpc_id     = module.vpc.vpc_id
#   subnet_ids = module.vpc.private_subnets
#
#   cluster_endpoint_public_access = true
#
#   eks_managed_node_groups = {
#     default = {
#       instance_types = ["t3.small"]
#       min_size       = 1
#       max_size       = 2
#       desired_size   = 1
#     }
#   }
#
#   enable_cluster_creator_admin_permissions = true
# }

# ── ECS Cluster ───────────────────────────────────────────────────────────────
# commented out — replaced by EKS cluster above

# resource "aws_ecs_cluster" "main" {
#   name = "flight-plan-${var.environment}"
#
#   setting {
#     name  = "containerInsights"
#     value = "enabled"
#   }
# }
#
# resource "aws_ecs_cluster_capacity_providers" "main" {
#   cluster_name       = aws_ecs_cluster.main.name
#   capacity_providers = ["FARGATE", "FARGATE_SPOT"]
#
#   default_capacity_provider_strategy {
#     capacity_provider = var.environment == "prod" ? "FARGATE" : "FARGATE_SPOT"
#     weight            = 1
#   }
# }

# ── CloudWatch Log Group ──────────────────────────────────────────────────────
# commented out — not needed for single-pod cost-saving setup; re-enable if log ingestion is required

# resource "aws_cloudwatch_log_group" "app" {
#   name              = "/ecs/flight-plan-backend-${var.environment}"
#   retention_in_days = var.environment == "prod" ? 90 : 14
# }

# ── IAM — Task Execution Role ─────────────────────────────────────────────────
# commented out — ECS task roles not needed; EKS node IAM role is managed by the eks module

# resource "aws_iam_role" "ecs_task_execution" {
#   name = "flight-plan-${var.environment}-task-execution"
#
#   assume_role_policy = jsonencode({
#     Version = "2012-10-17"
#     Statement = [{
#       Effect    = "Allow"
#       Principal = { Service = "ecs-tasks.amazonaws.com" }
#       Action    = "sts:AssumeRole"
#     }]
#   })
# }
#
# resource "aws_iam_role_policy_attachment" "ecs_task_execution_managed" {
#   role       = aws_iam_role.ecs_task_execution.name
#   policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
# }
#
# # Allow ECS to pull secrets from Secrets Manager
# resource "aws_iam_role_policy" "ecs_secrets" {
#   name = "ecs-secrets-access"
#   role = aws_iam_role.ecs_task_execution.id
#
#   policy = jsonencode({
#     Version = "2012-10-17"
#     Statement = [{
#       Effect = "Allow"
#       Action = [
#         "secretsmanager:GetSecretValue",
#         "kms:Decrypt"
#       ]
#       Resource = [
#         "arn:aws:secretsmanager:${var.aws_region}:${data.aws_caller_identity.current.account_id}:secret:flight-plan/${var.environment}/*"
#       ]
#     }]
#   })
# }

# ── IAM — Task Role (app runtime permissions) ─────────────────────────────────
# commented out — ECS task role not needed for EKS setup

# resource "aws_iam_role" "ecs_task" {
#   name = "flight-plan-${var.environment}-task"
#
#   assume_role_policy = jsonencode({
#     Version = "2012-10-17"
#     Statement = [{
#       Effect    = "Allow"
#       Principal = { Service = "ecs-tasks.amazonaws.com" }
#       Action    = "sts:AssumeRole"
#     }]
#   })
# }

# ── ElastiCache Redis (leader election) ───────────────────────────────────────

resource "aws_elasticache_subnet_group" "redis" {
  name       = "flight-plan-${var.environment}-redis"
  subnet_ids = module.vpc.private_subnets
}

resource "aws_security_group" "redis" {
  name   = "flight-plan-${var.environment}-redis-sg"
  vpc_id = module.vpc.vpc_id

  ingress {
    from_port   = 6379
    to_port     = 6379
    protocol    = "tcp"
    cidr_blocks = [module.vpc.vpc_cidr_block]                                        # was: security_groups = [aws_security_group.ecs_tasks.id] — allow from EKS nodes via VPC CIDR
  }
}

resource "aws_elasticache_cluster" "redis" {
  cluster_id           = "flight-plan-${var.environment}"
  engine               = "redis"
  node_type            = "cache.t3.micro"                                             # was: var.environment == "prod" ? "cache.t3.small" : "cache.t3.micro" — fixed to micro
  num_cache_nodes      = 1
  parameter_group_name = "default.redis7"
  engine_version       = "7.0"
  subnet_group_name    = aws_elasticache_subnet_group.redis.name
  security_group_ids   = [aws_security_group.redis.id]
}

# ── Security Groups ───────────────────────────────────────────────────────────
# commented out — ALB security groups not needed; ingress-nginx on EKS handles inbound traffic

# resource "aws_security_group" "alb" {
#   name   = "flight-plan-${var.environment}-alb-sg"
#   vpc_id = module.vpc.vpc_id
#
#   ingress {
#     from_port   = 443
#     to_port     = 443
#     protocol    = "tcp"
#     cidr_blocks = ["0.0.0.0/0"]
#   }
#   ingress {
#     from_port   = 80
#     to_port     = 80
#     protocol    = "tcp"
#     cidr_blocks = ["0.0.0.0/0"]   # redirect only; blocked at app layer
#   }
#   egress {
#     from_port   = 0
#     to_port     = 0
#     protocol    = "-1"
#     cidr_blocks = ["0.0.0.0/0"]
#   }
# }
#
# resource "aws_security_group" "ecs_tasks" {
#   name   = "flight-plan-${var.environment}-ecs-sg"
#   vpc_id = module.vpc.vpc_id
#
#   ingress {
#     from_port       = 8080
#     to_port         = 8080
#     protocol        = "tcp"
#     security_groups = [aws_security_group.alb.id]
#   }
#   egress {
#     from_port   = 0
#     to_port     = 0
#     protocol    = "-1"
#     cidr_blocks = ["0.0.0.0/0"]
#   }
# }

# ── ALB ───────────────────────────────────────────────────────────────────────
# commented out — ALB replaced by ingress-nginx NLB provisioned by EKS

# resource "aws_lb" "main" {
#   name               = "flight-plan-${var.environment}"
#   internal           = false
#   load_balancer_type = "application"
#   security_groups    = [aws_security_group.alb.id]
#   subnets            = module.vpc.public_subnets
#
#   enable_deletion_protection = var.environment == "prod"
# }
#
# resource "aws_lb_target_group" "app" {
#   name        = "flight-plan-${var.environment}-tg"
#   port        = 8080
#   protocol    = "HTTP"
#   vpc_id      = module.vpc.vpc_id
#   target_type = "ip"
#
#   health_check {
#     path                = "/api/health"
#     healthy_threshold   = 2
#     unhealthy_threshold = 3
#     interval            = 30
#     timeout             = 5
#     matcher             = "200"
#   }
#
#   deregistration_delay = 30
# }
#
# resource "aws_lb_listener" "https" {
#   load_balancer_arn = aws_lb.main.arn
#   port              = 443
#   protocol          = "HTTPS"
#   ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
#   certificate_arn   = var.acm_certificate_arn
#
#   default_action {
#     type             = "forward"
#     target_group_arn = aws_lb_target_group.app.arn
#   }
# }
#
# resource "aws_lb_listener" "http_redirect" {
#   load_balancer_arn = aws_lb.main.arn
#   port              = 80
#   protocol          = "HTTP"
#
#   default_action {
#     type = "redirect"
#     redirect {
#       port        = "443"
#       protocol    = "HTTPS"
#       status_code = "HTTP_301"
#     }
#   }
# }

# ── ECS Task Definition ───────────────────────────────────────────────────────
# commented out — workloads now run as Kubernetes Deployments on EKS (k8s/backend.yaml)

# resource "aws_ecs_task_definition" "app" {
#   family                   = "flight-plan-backend-${var.environment}"
#   requires_compatibilities = ["FARGATE"]
#   network_mode             = "awsvpc"
#   cpu                      = var.task_cpu
#   memory                   = var.task_memory
#   execution_role_arn       = aws_iam_role.ecs_task_execution.arn
#   task_role_arn            = aws_iam_role.ecs_task.arn
#
#   container_definitions = jsonencode([{
#     name      = "flight-plan-backend"
#     image     = "${aws_ecr_repository.app.repository_url}:${var.app_image_tag}"
#     essential = true
#
#     portMappings = [{
#       containerPort = 8080
#       protocol      = "tcp"
#     }]
#
#     environment = [
#       { name = "SPRING_PROFILES_ACTIVE",           value = var.environment == "prod" ? "prod" : "dev" },
#       { name = "SERVER_PORT",                       value = "8080" },
#       { name = "SECURITY_CORS_ALLOWED_ORIGINS",     value = var.cors_allowed_origins }
#     ]
#
#     # Inject Redis URL from Secrets Manager at task start
#     secrets = [
#       { name = "SPRING_DATA_REDIS_URL", valueFrom = "arn:aws:secretsmanager:${var.aws_region}:${data.aws_caller_identity.current.account_id}:secret:flight-plan/${var.environment}/redis-url" }
#     ]
#
#     logConfiguration = {
#       logDriver = "awslogs"
#       options = {
#         "awslogs-group"         = aws_cloudwatch_log_group.app.name
#         "awslogs-region"        = var.aws_region
#         "awslogs-stream-prefix" = "ecs"
#       }
#     }
#
#     healthCheck = {
#       command     = ["CMD-SHELL", "wget -qO- http://localhost:8080/api/health || exit 1"]
#       interval    = 30
#       timeout     = 5
#       retries     = 3
#       startPeriod = 60
#     }
#
#     # IM8 S3: read-only root filesystem, drop all capabilities
#     readonlyRootFilesystem = true
#     linuxParameters = {
#       capabilities = { drop = ["ALL"] }
#     }
#   }])
# }

# ── ECS Service ───────────────────────────────────────────────────────────────
# commented out — replaced by Kubernetes Deployment in k8s/backend.yaml

# resource "aws_ecs_service" "app" {
#   name            = "flight-plan-backend-${var.environment}"
#   cluster         = aws_ecs_cluster.main.id
#   task_definition = aws_ecs_task_definition.app.arn
#   desired_count   = var.desired_count
#   launch_type     = "FARGATE"
#
#   network_configuration {
#     subnets          = module.vpc.private_subnets
#     security_groups  = [aws_security_group.ecs_tasks.id]
#     assign_public_ip = false
#   }
#
#   load_balancer {
#     target_group_arn = aws_lb_target_group.app.arn
#     container_name   = "flight-plan-backend"
#     container_port   = 8080
#   }
#
#   deployment_circuit_breaker {
#     enable   = true
#     rollback = true     # auto-rollback on failed deployment
#   }
#
#   deployment_controller {
#     type = "ECS"        # switch to CODE_DEPLOY for blue/green in prod
#   }
#
#   depends_on = [aws_lb_listener.https]
#
#   lifecycle {
#     ignore_changes = [task_definition, desired_count]
#   }
# }

# ── Auto Scaling ──────────────────────────────────────────────────────────────
# commented out — ECS auto scaling removed; HPA in k8s/hpa.yaml handles pod scaling on EKS

# resource "aws_appautoscaling_target" "ecs" {
#   max_capacity       = var.environment == "prod" ? 10 : 4
#   min_capacity       = var.environment == "prod" ? 2  : 1
#   resource_id        = "service/${aws_ecs_cluster.main.name}/${aws_ecs_service.app.name}"
#   scalable_dimension = "ecs:service:DesiredCount"
#   service_namespace  = "ecs"
# }
#
# resource "aws_appautoscaling_policy" "cpu" {
#   name               = "flight-plan-${var.environment}-cpu-scaling"
#   policy_type        = "TargetTrackingScaling"
#   resource_id        = aws_appautoscaling_target.ecs.resource_id
#   scalable_dimension = aws_appautoscaling_target.ecs.scalable_dimension
#   service_namespace  = aws_appautoscaling_target.ecs.service_namespace
#
#   target_tracking_scaling_policy_configuration {
#     target_value       = 70.0
#     scale_in_cooldown  = 300
#     scale_out_cooldown = 60
#
#     predefined_metric_specification {
#       predefined_metric_type = "ECSServiceAverageCPUUtilization"
#     }
#   }
# }

# ── Outputs ───────────────────────────────────────────────────────────────────

# output "alb_dns_name"      { value = aws_lb.main.dns_name }                        # commented out — ALB removed
output "ecr_repository_url" { value = aws_ecr_repository.app.repository_url }
output "redis_endpoint"     { value = aws_elasticache_cluster.redis.cache_nodes[0].address }
output "eks_cluster_name"   { value = module.eks.cluster_name }
output "eks_cluster_endpoint" { value = module.eks.cluster_endpoint }
# output "ecs_cluster_name"  { value = aws_ecs_cluster.main.name }                   # commented out — ECS cluster removed
# output "ecs_service_name"  { value = aws_ecs_service.app.name }                    # commented out — ECS service removed
