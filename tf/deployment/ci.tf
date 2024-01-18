# Resources for deployment from GitHub action

# resource "aws_iam_group" "ci" {
#   name = "ci"
# }

# resource "aws_iam_group_policy" "access-to-ecs" {
#   name  = "openmind-ci-access-ecs"
#   group = aws_iam_group.ci.id

#   policy = <<EOF
# {
#   "Version": "2012-10-17",
#   "Statement": [
#    {

# 	 	"Action": [
#                "iam:PassRole"
#               ],
# 		"Resource": ["${aws_iam_role.ecs-task-role.arn}",
#                  "${aws_iam_role.ecs-execution-role.arn}"],
# 		"Effect": "Allow",
# 		"Sid": "passrole"
# 	 },
#    {

# 	 	"Action": [
#                "ecs:RegisterTaskDefinition",
#                "ecs:DescribeServices",
#                "ecs:UpdateService"
#               ],
# 		"Resource": ["*"],
# 		"Effect": "Allow",
# 		"Sid": "ecsaccess"
# 	 },
#     {
# 		"Action": [
#               "ecr:GetAuthorizationToken",
#               "ecr:BatchCheckLayerAvailability",
#               "ecr:GetDownloadUrlForLayer",
#               "ecr:GetRepositoryPolicy",
#               "ecr:DescribeRepositories",
#               "ecr:ListImages",
#               "ecr:DescribeImages",
#               "ecr:BatchGetImage",
#               "ecr:GetLifecyclePolicy",
#               "ecr:GetLifecyclePolicyPreview",
#               "ecr:ListTagsForResource",
#               "ecr:DescribeImageScanFindings",
#               "ecr:InitiateLayerUpload",
#               "ecr:UploadLayerPart",
#               "ecr:CompleteLayerUpload",
#               "ecr:PutImage"
#               ],
# 		"Resource": ["*"],
# 		"Effect": "Allow",
# 		"Sid": "ECRAccess"
# 		},
#     {
#       "Effect": "Allow",
#       "Action": ["s3:ListBucket"],
#       "Resource": ["${aws_s3_bucket.openmind-test-data.arn}"]
#     },
#     {
#       "Effect": "Allow",
#       "Action": [
#         "s3:PutObject",
#         "s3:GetObject",
#         "s3:DeleteObject"
#       ],
#       "Resource": ["${aws_s3_bucket.openmind-test-data.arn}/*"]
#     }
#   ]
# }
# EOF
# }
