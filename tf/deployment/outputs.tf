output "ecr_url" {
  value = aws_ecr_repository.openmind.repository_url
}

output "openmind_task_arn" {
  value = aws_iam_role.ecs-task-role.arn
}

output "openmind_execution_arn" {
  value = aws_iam_role.ecs-execution-role.arn
}
