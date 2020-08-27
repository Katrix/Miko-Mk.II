package miko.web.models

import ackcord.CacheSnapshot
import ackcord.data.UserId

case class ViewInfo(userId: UserId, cache: CacheSnapshot)
