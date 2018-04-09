from __future__ import print_function
from sys import exit
import stashy
import argparse


def pull_request_connect(connection, project, repository, pull_request):
    return connection.projects[project].repos[repository].pull_requests[pull_request]


def parse_description(pull_request_connection):
    description_list = pull_request_connection.get()['description']
    return [i.encode('utf8').lower() for i in description_list.split()]


def is_mergeable(pull_request_connection):
    return pull_request_connection.can_merge()


def is_approved(pull_request_connection):
    approvers_list = pull_request_connection.get()['reviewers']
    approved_list = [i['status'].encode('utf8').lower() for i in approvers_list]
    if 'unapproved' in approved_list:
        return False
    else:
        return True


def get_branch_name(pull_request_connection):
    return pull_request_connection.get()['fromRef']['displayId'].encode('utf8')


def get_author_email(pull_request_connection):
    return pull_request_connection.get()['author']['user']['emailAddress'].encode('utf8')


def merge_pr(pull_request_connection):
    try:
        pull_request_connection.merge(version=int(pull_request_connection.get()['version']))
    except:
        exit(-1)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    # params for parser
    parser.add_argument('--server-url -s', action='store', dest='serverURL', help='bitbucket server url')
    parser.add_argument('--user -u', action='store', dest='userName', help='bitbucket user name')
    parser.add_argument('--password -p', action='store', dest='userPass', help='bitbucket user password')
    parser.add_argument('--project', action='store', dest='project', help='bitbucket project')
    parser.add_argument('--repo', action='store', dest='repo', help='bitbucket repository')
    parser.add_argument('--pr', action='store', dest='pr_id', help='bitbucket pull requst id')
    parser.add_argument('--get-labels', action='store_true',
                        dest='getLabels', help='bitbucket description parse to get list')
    parser.add_argument('--is-mergeable', action='store_true',
                        dest='isMergeable', help='bitbucket pull request is mergeable')
    parser.add_argument('--is-approved', action='store_true',
                        dest='isApproved', help='bitbucket pull request is approved by all reviewers')
    parser.add_argument('--get-branch-name', action='store_true',
                        dest='getBranchName', help='bitbucket pull request branch name')
    parser.add_argument('--get_author_email', action='store_true',
                        dest='getAuthorEmail', help='bitbucket pull request author email')
    parser.add_argument('--merge-pr', action='store_true',
                        dest='mergePR', help='bitbucket pull request merge')

    args = parser.parse_args()

    stash = stashy.connect(args.serverURL, args.userName, args.userPass)
    pr_connection = pull_request_connect(stash, args.project, args.repo, args.pr_id)

    if args.getLabels:
        print(",".join(parse_description(pr_connection)))
    if args.isMergeable:
        print(is_mergeable(pr_connection))
    if args.isApproved:
        print(is_approved(pr_connection))
    if args.getBranchName:
        print(get_branch_name(pr_connection))
    if args.getAuthorEmail:
        print(get_author_email(pr_connection))
    if args.mergePR:
        merge_pr(pr_connection)

# [TODO] add dump parse
