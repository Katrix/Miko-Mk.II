package miko.web.models

import ackcord.CacheSnapshot
import ackcord.data.User

case class ViewInfo(user: User, cache: CacheSnapshot)
