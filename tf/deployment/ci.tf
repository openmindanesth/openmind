# Resources for deployment from GitHub action

resource "aws_iam_user" "ci" {
  name = "openmind-${var.env}-ci"
}

resource "aws_iam_access_key" "ci" {
  user = aws_iam_user.ci.name
}

resource "aws_iam_policy" "ci" {
  name        = "${var.env}-ci"
  path        = "/"
  description = "push to ecr and update ssm."

  policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Action = [
          "ssm:GetParameters"
        ],
        Resource = [data.aws_ssm_parameter.openmind-container-id.arn],
        Effect   = "Allow",
        Sid      = "SsmUpdate"
      },
      {
        Action = [
          "ecr:GetAuthorizationToken",
          "ecr:BatchCheckLayerAvailability",
          "ecr:GetDownloadUrlForLayer",
          "ecr:GetRepositoryPolicy",
          "ecr:DescribeRepositories",
          "ecr:ListImages",
          "ecr:DescribeImages",
          "ecr:BatchGetImage",
          "ecr:GetLifecyclePolicy",
          "ecr:GetLifecyclePolicyPreview",
          "ecr:ListTagsForResource",
          "ecr:DescribeImageScanFindings",
          "ecr:InitiateLayerUpload",
          "ecr:UploadLayerPart",
          "ecr:CompleteLayerUpload",
          "ecr:PutImage"
        ],
        Resource = [aws_ecr_repository.openmind.arn],
        Effect   = "Allow",
        Sid      = "ECRAccess"
      }
    ]
  })
}

resource "aws_iam_user_policy_attachment" "ci" {
  user = aws_iam_user.ci.name
  policy_arn = aws_iam_policy.ci.arn
}
